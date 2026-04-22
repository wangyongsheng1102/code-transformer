package com.example.transformer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 用于分析“升级前后代码”并输出可规则化迁移模式的简易 CLI。
 */
public class PortalRuleAnalyzerCli {

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        if (options == null) {
            printUsage();
            return;
        }

        AnalysisResult result = analyze(options.beforeRoot(), options.afterRoot());
        printTop("Removed imports", result.removedImports());
        printTop("Added imports", result.addedImports());
        printTop("Extends changes", result.extendsChanges());
        printTop("Added class annotations", result.addedClassAnnotations());
        printTop("Added method annotations", result.addedMethodAnnotations());
        printTop("Removed method names", result.removedMethodNames());
        printTop("Added method names", result.addedMethodNames());
        printTop("Method rename candidates", result.methodRenames());
        printTop("Body replacement candidates", result.bodyReplacementCandidates());

        if (options.reportOut() != null) {
            String report = renderReport(result, options.beforeRoot(), options.afterRoot());
            Path parent = options.reportOut().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(options.reportOut(), report);
            System.out.println("Report written: " + options.reportOut());
        }
    }

    public static AnalysisResult analyze(Path beforeRoot, Path afterRoot) throws IOException {
        Map<String, Path> beforeJava = collectJavaFiles(beforeRoot);
        Map<String, Path> afterJava = collectJavaFiles(afterRoot);

        Set<String> common = beforeJava.keySet().stream()
                .filter(afterJava::containsKey)
                .collect(Collectors.toSet());

        Map<String, Integer> removedImports = new HashMap<>();
        Map<String, Integer> addedImports = new HashMap<>();
        Map<String, Integer> extendsChanges = new HashMap<>();
        Map<String, Integer> addedClassAnnotations = new HashMap<>();
        Map<String, Integer> addedMethodAnnotations = new HashMap<>();
        Map<String, Integer> removedMethodNames = new HashMap<>();
        Map<String, Integer> addedMethodNames = new HashMap<>();
        Map<String, Integer> methodRenames = new HashMap<>();
        Map<String, Integer> bodyReplacementCandidates = new HashMap<>();

        for (String rel : common) {
            String before = Files.readString(beforeJava.get(rel));
            String after = Files.readString(afterJava.get(rel));
            if (before.equals(after)) {
                continue;
            }

            Set<String> beforeImports = JavaTextPatterns.extractImports(before);
            Set<String> afterImports = JavaTextPatterns.extractImports(after);

            for (String removed : beforeImports) {
                if (!afterImports.contains(removed)) {
                    removedImports.merge(removed, 1, Integer::sum);
                }
            }
            for (String added : afterImports) {
                if (!beforeImports.contains(added)) {
                    addedImports.merge(added, 1, Integer::sum);
                }
            }

            String beforeExtends = JavaTextPatterns.extractExtendsSimpleName(before);
            String afterExtends = JavaTextPatterns.extractExtendsSimpleName(after);
            if (!beforeExtends.equals(afterExtends)) {
                String key = (beforeExtends.isEmpty() ? "<none>" : beforeExtends)
                        + " -> "
                        + (afterExtends.isEmpty() ? "<none>" : afterExtends);
                extendsChanges.merge(key, 1, Integer::sum);
            }

            Set<String> beforeAnnotations = JavaTextPatterns.extractClassAnnotations(before);
            Set<String> afterAnnotations = JavaTextPatterns.extractClassAnnotations(after);
            for (String ann : afterAnnotations) {
                if (!beforeAnnotations.contains(ann)) {
                    addedClassAnnotations.merge(ann, 1, Integer::sum);
                }
            }

            Map<String, JavaTextPatterns.MethodSignature> beforeMethods = JavaTextPatterns.extractMethods(before);
            Map<String, JavaTextPatterns.MethodSignature> afterMethods = JavaTextPatterns.extractMethods(after);

            for (String removed : beforeMethods.keySet()) {
                if (!afterMethods.containsKey(removed)) {
                    removedMethodNames.merge(removed, 1, Integer::sum);
                }
            }
            for (String added : afterMethods.keySet()) {
                if (!beforeMethods.containsKey(added)) {
                    addedMethodNames.merge(added, 1, Integer::sum);
                }
            }

            // 方法重命名候选：同参数个数+同返回类型，但方法名变化
            for (JavaTextPatterns.MethodSignature oldSig : beforeMethods.values()) {
                for (JavaTextPatterns.MethodSignature newSig : afterMethods.values()) {
                    if (oldSig.name.equals(newSig.name)) {
                        continue;
                    }
                    if (oldSig.parameterCount == newSig.parameterCount
                            && oldSig.returnType.equals(newSig.returnType)) {
                        methodRenames.merge(oldSig.name + " -> " + newSig.name, 1, Integer::sum);
                    }
                }
            }

            for (String methodName : beforeMethods.keySet()) {
                if (!afterMethods.containsKey(methodName)) {
                    continue;
                }
                JavaTextPatterns.MethodSignature beforeSig = beforeMethods.get(methodName);
                JavaTextPatterns.MethodSignature afterSig = afterMethods.get(methodName);
                for (String ann : afterSig.annotations) {
                    if (!beforeSig.annotations.contains(ann)) {
                        addedMethodAnnotations.merge(ann, 1, Integer::sum);
                    }
                }
            }

            // 代码体替换候选（限定高置信模式）
            for (Map.Entry<String, String> e : JavaTextPatterns.extractCommonBodyReplacements(before, after).entrySet()) {
                bodyReplacementCandidates.merge(e.getKey() + " => " + e.getValue(), 1, Integer::sum);
            }
        }

        return new AnalysisResult(
                sortByCountDesc(removedImports),
                sortByCountDesc(addedImports),
                sortByCountDesc(extendsChanges),
                sortByCountDesc(addedClassAnnotations),
                sortByCountDesc(addedMethodAnnotations),
                sortByCountDesc(removedMethodNames),
                sortByCountDesc(addedMethodNames),
                sortByCountDesc(methodRenames),
                sortByCountDesc(bodyReplacementCandidates)
        );
    }

    private static Map<String, Path> collectJavaFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toMap(
                            p -> root.relativize(p).toString().replace('\\', '/'),
                            p -> p
                    ));
        }
    }

    private static LinkedHashMap<String, Integer> sortByCountDesc(Map<String, Integer> source) {
        return source.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Integer.compare(b.getValue(), a.getValue());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return a.getKey().compareTo(b.getKey());
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (x, y) -> x,
                        LinkedHashMap::new
                ));
    }

    private static void printTop(String title, Map<String, Integer> map) {
        System.out.println("== " + title + " ==");
        int limit = 20;
        int idx = 0;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            System.out.println(e.getValue() + " " + e.getKey());
            idx++;
            if (idx >= limit) {
                break;
            }
        }
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp <cp> com.example.transformer.cli.PortalRuleAnalyzerCli "
                + "--before <beforeRoot> --after <afterRoot> [--report-out <reportPath>]");
    }

    private static String renderReport(AnalysisResult result, Path beforeRoot, Path afterRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Portal2 迁移规则抽取报告\n\n");
        sb.append("- before: ").append(beforeRoot).append("\n");
        sb.append("- after: ").append(afterRoot).append("\n\n");

        appendTopSection(sb, "Removed imports", result.removedImports(), 30);
        appendTopSection(sb, "Added imports", result.addedImports(), 30);
        appendTopSection(sb, "Extends changes", result.extendsChanges(), 20);
        appendTopSection(sb, "Added class annotations", result.addedClassAnnotations(), 20);
        appendTopSection(sb, "Added method annotations", result.addedMethodAnnotations(), 20);
        appendTopSection(sb, "Removed method names", result.removedMethodNames(), 30);
        appendTopSection(sb, "Added method names", result.addedMethodNames(), 30);
        appendTopSection(sb, "Method rename candidates", result.methodRenames(), 40);
        appendTopSection(sb, "Body replacement candidates", result.bodyReplacementCandidates(), 60);

        sb.append("## 可落地规则建议\n\n");
        sb.append("1. `javax.validation.*` 系列导包统一替换为 `jakarta.validation.*`。\n");
        sb.append("2. `AbstractAction` 派生类替换为 `BaseController` 并补 `@Controller`。\n");
        sb.append("3. `AbstractInterceptor` 派生类移除继承、实现 `HandlerInterceptor` 并补 `@Component`。\n");
        sb.append("4. 去除 Struts 导包（`com.opensymphony.xwork2.*` / `org.apache.struts2.*`）并补充 Servlet/Jakarta 导包。\n");
        sb.append("5. 方法级规则：`execute/next/regist/update` 等高频旧方法按动作（DELETE/RENAME/MODIFY）逐步迁移。\n");
        sb.append("6. 方法体替换规则：高置信语句（如 `sessionMap.get(` -> `sessionMap.getAttribute(`）可批量替换。\n");
        return sb.toString();
    }

    private static void appendTopSection(StringBuilder sb, String title, Map<String, Integer> map, int limit) {
        sb.append("## ").append(title).append("\n\n");
        int idx = 0;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            sb.append("- ").append(e.getValue()).append(" × `").append(e.getKey()).append("`\n");
            idx++;
            if (idx >= limit) {
                break;
            }
        }
        sb.append("\n");
    }

    public record AnalysisResult(
            Map<String, Integer> removedImports,
            Map<String, Integer> addedImports,
            Map<String, Integer> extendsChanges,
            Map<String, Integer> addedClassAnnotations,
            Map<String, Integer> addedMethodAnnotations,
            Map<String, Integer> removedMethodNames,
            Map<String, Integer> addedMethodNames,
            Map<String, Integer> methodRenames,
            Map<String, Integer> bodyReplacementCandidates
    ) {
    }

    private record CliOptions(Path beforeRoot, Path afterRoot, Path reportOut) {
        static CliOptions parse(String[] args) {
            Path before = null;
            Path after = null;
            Path reportOut = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--before" -> before = Path.of(args[++i]).toAbsolutePath();
                    case "--after" -> after = Path.of(args[++i]).toAbsolutePath();
                    case "--report-out" -> reportOut = Path.of(args[++i]).toAbsolutePath();
                    default -> {
                    }
                }
            }
            if (before == null || after == null) {
                return null;
            }
            return new CliOptions(before, after, reportOut);
        }
    }

    /**
     * 文本层面的简易 Java 模式提取器（无需完整 AST）。
     */
    static class JavaTextPatterns {
        private static final java.util.regex.Pattern IMPORT_PATTERN =
                java.util.regex.Pattern.compile("^\\s*import\\s+([^;]+);", java.util.regex.Pattern.MULTILINE);
        private static final java.util.regex.Pattern CLASS_PATTERN =
                java.util.regex.Pattern.compile(
                        "\\bclass\\s+\\w+\\s*(?:extends\\s+([\\w\\.<>]+))?\\s*(?:implements\\s+[^\\{]+)?\\{",
                        java.util.regex.Pattern.DOTALL
                );
        private static final java.util.regex.Pattern ANNOTATION_PATTERN =
                java.util.regex.Pattern.compile("^\\s*@([\\w\\.]+)", java.util.regex.Pattern.MULTILINE);
        private static final Pattern METHOD_SIGNATURE_PATTERN = Pattern.compile(
                "(?m)^\\s*(public|protected|private)\\s+([\\w<>\\[\\], ?]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [^{]+)?\\{"
        );

        static Set<String> extractImports(String text) {
            var m = IMPORT_PATTERN.matcher(text);
            var out = new java.util.HashSet<String>();
            while (m.find()) {
                out.add(m.group(1).trim());
            }
            return out;
        }

        static String extractExtendsSimpleName(String text) {
            var m = CLASS_PATTERN.matcher(text);
            if (!m.find()) {
                return "";
            }
            String ext = m.group(1);
            if (ext == null || ext.isBlank()) {
                return "";
            }
            int idx = ext.lastIndexOf('.');
            return idx >= 0 ? ext.substring(idx + 1) : ext;
        }

        static Set<String> extractClassAnnotations(String text) {
            var classMatcher = CLASS_PATTERN.matcher(text);
            if (!classMatcher.find()) {
                return Collections.emptySet();
            }
            String pre = text.substring(0, classMatcher.start());
            String[] lines = pre.split("\\R");
            int from = Math.max(0, lines.length - 40);
            String tail = String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));

            var out = new java.util.HashSet<String>();
            var annMatcher = ANNOTATION_PATTERN.matcher(tail);
            while (annMatcher.find()) {
                String ann = annMatcher.group(1).trim();
                int idx = ann.lastIndexOf('.');
                out.add(idx >= 0 ? ann.substring(idx + 1) : ann);
            }
            return out;
        }

        static Map<String, MethodSignature> extractMethods(String text) {
            var methodMatcher = METHOD_SIGNATURE_PATTERN.matcher(text);
            Map<String, MethodSignature> methods = new LinkedHashMap<>();
            while (methodMatcher.find()) {
                String returnType = methodMatcher.group(2).trim();
                String name = methodMatcher.group(3).trim();
                String params = methodMatcher.group(4).trim();
                int paramCount = params.isBlank() ? 0 : params.split(",").length;

                // 向前回溯提取紧邻方法签名前的注解
                int start = methodMatcher.start();
                int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1));
                String before = text.substring(Math.max(0, lineStart - 500), Math.max(0, lineStart));
                var annMatcher = ANNOTATION_PATTERN.matcher(before);
                Set<String> annotations = new java.util.HashSet<>();
                while (annMatcher.find()) {
                    String ann = annMatcher.group(1).trim();
                    int idx = ann.lastIndexOf('.');
                    annotations.add(idx >= 0 ? ann.substring(idx + 1) : ann);
                }

                methods.put(name, new MethodSignature(name, returnType, paramCount, annotations));
            }
            return methods;
        }

        static Map<String, String> extractCommonBodyReplacements(String before, String after) {
            Map<String, String> replacements = new LinkedHashMap<>();
            // 高置信迁移：sessionMap.get(...) -> sessionMap.getAttribute(...)
            if (before.contains("sessionMap.get(") && after.contains("sessionMap.getAttribute(")) {
                replacements.put("sessionMap.get(", "sessionMap.getAttribute(");
            }
            // 高置信迁移：return XXX; -> return findForward(XXX);
            if (before.contains("return INPUT;") && after.contains("return findForward(INPUT);")) {
                replacements.put("return INPUT;", "return findForward(INPUT);");
            }
            if (before.contains("return SUCCESS;") && after.contains("return findForward(SUCCESS);")) {
                replacements.put("return SUCCESS;", "return findForward(SUCCESS);");
            }
            if (before.contains("return ERROR;") && after.contains("return findForward(ERROR);")) {
                replacements.put("return ERROR;", "return findForward(ERROR);");
            }
            return replacements;
        }

        record MethodSignature(String name, String returnType, int parameterCount, Set<String> annotations) {
        }
    }
}
