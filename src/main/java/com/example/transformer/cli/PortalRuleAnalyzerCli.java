package com.example.transformer.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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

        AnalysisResult result = null;
        if (options.afterRoot() != null) {
            result = analyze(options.beforeRoot(), options.afterRoot());
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
        } else if (options.reportOut() != null) {
            System.err.println("--report-out requires --after.");
            return;
        }

        if (options.relationOut() != null) {
            RelationManifest manifest = buildRelationManifest(options.beforeRoot(), options.afterRoot());
            Path parent = options.relationOut().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(options.relationOut(), mapper.writeValueAsString(manifest));
            System.out.println("Relation manifest written: " + options.relationOut());
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
        if (!Files.exists(root)) {
            return Map.of();
        }
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
                + "--before <beforeRoot> [--after <afterRoot>] "
                + "[--report-out <reportPath>] [--relation-out <relationManifest.json>]");
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

    private static RelationManifest buildRelationManifest(Path beforeRoot, Path afterRoot) throws IOException {
        List<ModuleRelation> modules = new ArrayList<>();
        List<String> moduleNames = List.of("EPTL_ADMIN", "EPTL_WEB", "EPTL_SERVICE");
        for (String moduleName : moduleNames) {
            Path moduleRoot = beforeRoot.resolve(moduleName);
            if (!Files.exists(moduleRoot)) {
                continue;
            }
            modules.add(buildModuleRelation(beforeRoot, moduleName));
        }

        List<CrossModuleRelation> crossRelations = buildCrossModuleRelations(modules);
        return new RelationManifest(
                "1.0.0",
                Instant.now().toString(),
                new SourceRoots(beforeRoot.toString(), afterRoot == null ? null : afterRoot.toString()),
                modules,
                crossRelations
        );
    }

    private static ModuleRelation buildModuleRelation(Path beforeRoot, String moduleName) throws IOException {
        Path moduleRoot = beforeRoot.resolve(moduleName);
        String moduleType = moduleName.contains("SERVICE") ? "SERVICE" : "WEB";

        Map<String, String> configFiles = new LinkedHashMap<>();
        List<String> componentScans = new ArrayList<>();
        List<ActionMapping> actionMappings = new ArrayList<>();
        List<BeanDependency> beanDependencies = new ArrayList<>();
        List<ServiceCall> serviceCalls = new ArrayList<>();
        List<ControllerEndpoint> controllerEndpoints = new ArrayList<>();
        String servletApiPrefix = null;

        if (moduleName.equals("EPTL_SERVICE")) {
            Path webXml = moduleRoot.resolve("src/main/webapp/WEB-INF/web.xml");
            Path beansBiz = moduleRoot.resolve("src/main/resources/META-INF/spring/beans-biz.xml");
            Path beansWebMvc = moduleRoot.resolve("src/main/resources/META-INF/spring/beans-webmvc.xml");

            addExistingPath(configFiles, "webXml", beforeRoot, webXml);
            addExistingPath(configFiles, "beansBizXml", beforeRoot, beansBiz);
            addExistingPath(configFiles, "beansWebMvcXml", beforeRoot, beansWebMvc);

            if (Files.exists(beansBiz)) {
                String xml = Files.readString(beansBiz);
                componentScans.addAll(extractComponentScans(xml));
            }
            if (Files.exists(beansWebMvc)) {
                String xml = Files.readString(beansWebMvc);
                componentScans.addAll(extractComponentScans(xml));
            }
            if (Files.exists(webXml)) {
                servletApiPrefix = extractServletApiPrefix(Files.readString(webXml));
            }

            Path serviceJavaRoot = moduleRoot.resolve("src/main/java");
            if (Files.exists(serviceJavaRoot)) {
                Map<String, Path> javaFiles = collectJavaFiles(serviceJavaRoot);
                for (Map.Entry<String, Path> entry : javaFiles.entrySet()) {
                    if (!entry.getKey().contains("/controller/")) {
                        continue;
                    }
                    String content = Files.readString(entry.getValue());
                    beanDependencies.addAll(extractBeanDependencies(content, entry.getValue(), beforeRoot, true));
                    ControllerEndpoint endpoint = extractControllerEndpoint(content, entry.getValue(), beforeRoot);
                    if (endpoint != null) {
                        controllerEndpoints.add(endpoint);
                    }
                }
            }
        } else {
            Path webXml = moduleRoot.resolve("WebContent/WEB-INF/web.xml");
            Path appContext = moduleRoot.resolve("WebContent/WEB-INF/spring/applicationContext.xml");
            Path strutsXml = moduleRoot.resolve("src/main/resources/struts.xml");
            Path configLocal = moduleRoot.resolve("src/main/resources/config_local.properties");

            addExistingPath(configFiles, "webXml", beforeRoot, webXml);
            addExistingPath(configFiles, "applicationContextXml", beforeRoot, appContext);
            addExistingPath(configFiles, "strutsXml", beforeRoot, strutsXml);
            addExistingPath(configFiles, "configLocalProperties", beforeRoot, configLocal);

            if (Files.exists(appContext)) {
                String xml = Files.readString(appContext);
                componentScans.addAll(extractComponentScans(xml));
            }
            if (Files.exists(strutsXml)) {
                String struts = Files.readString(strutsXml);
                actionMappings.addAll(extractActionMappings(struts));
            }

            Map<String, String> properties = Files.exists(configLocal)
                    ? loadProperties(configLocal)
                    : Map.of();
            String apiBaseUrl = properties.get("api.url");

            Path webJavaRoot = moduleRoot.resolve("src/main/java");
            if (Files.exists(webJavaRoot)) {
                Map<String, Path> javaFiles = collectJavaFiles(webJavaRoot);
                for (Map.Entry<String, Path> entry : javaFiles.entrySet()) {
                    String rel = entry.getKey();
                    String content = Files.readString(entry.getValue());
                    if (rel.contains("/action/") || rel.contains("/controller/")) {
                        beanDependencies.addAll(extractBeanDependencies(content, entry.getValue(), beforeRoot, true));
                    }
                    if (rel.contains("/service/")) {
                        serviceCalls.addAll(extractServiceCalls(content, entry.getValue(), beforeRoot, apiBaseUrl, properties));
                    }
                }
            }
        }

        return new ModuleRelation(
                moduleName,
                moduleType,
                configFiles,
                deduplicate(componentScans),
                actionMappings,
                beanDependencies,
                serviceCalls,
                controllerEndpoints,
                servletApiPrefix
        );
    }

    private static List<CrossModuleRelation> buildCrossModuleRelations(List<ModuleRelation> modules) {
        Map<String, ControllerEndpoint> serviceEndpointIndex = new HashMap<>();
        String servicePrefix = "";
        for (ModuleRelation module : modules) {
            if (!"SERVICE".equals(module.moduleType())) {
                continue;
            }
            servicePrefix = module.servletApiPrefix() == null ? "" : module.servletApiPrefix();
            for (ControllerEndpoint endpoint : module.controllerEndpoints()) {
                serviceEndpointIndex.put(normalizePath(endpoint.requestPath()), endpoint);
            }
        }

        List<CrossModuleRelation> out = new ArrayList<>();
        for (ModuleRelation module : modules) {
            if ("SERVICE".equals(module.moduleType())) {
                continue;
            }
            for (ServiceCall call : module.serviceCalls()) {
                String endpointPath = normalizePath(call.endpointPath());
                ControllerEndpoint target = serviceEndpointIndex.get(endpointPath);
                String targetPath = call.endpointPath() == null
                        ? null
                        : joinPaths(servicePrefix, endpointPath);
                out.add(new CrossModuleRelation(
                        module.moduleName(),
                        call.serviceClass(),
                        call.methodName(),
                        call.propertyKey(),
                        targetPath,
                        target == null ? null : "EPTL_SERVICE",
                        target == null ? null : target.controllerClass(),
                        call.resolvedUrl()
                ));
            }
        }
        return out;
    }

    private static List<ActionMapping> extractActionMappings(String strutsText) {
        Pattern actionPattern = Pattern.compile("<action\\s+([^>]*\\bname=\"[^\"]+\"[^>]*)>(.*?)</action>", Pattern.DOTALL);
        Pattern interceptorPattern = Pattern.compile("<interceptor-ref\\s+name=\"([^\"]+)\"");
        Pattern resultPattern = Pattern.compile("<result\\b([^>]*)>(.*?)</result>", Pattern.DOTALL);
        Pattern expressionPattern = Pattern.compile("\\$\\{([^}]+)}");

        List<ActionMapping> mappings = new ArrayList<>();
        Matcher actionMatcher = actionPattern.matcher(strutsText);
        while (actionMatcher.find()) {
            String attrs = actionMatcher.group(1);
            String body = actionMatcher.group(2);
            String actionName = getAttribute(attrs, "name");
            String actionClass = getAttribute(attrs, "class");
            String methodExpr = getAttribute(attrs, "method");

            List<String> interceptors = new ArrayList<>();
            Matcher interceptorMatcher = interceptorPattern.matcher(body);
            while (interceptorMatcher.find()) {
                interceptors.add(interceptorMatcher.group(1).trim());
            }

            List<ResultMapping> results = new ArrayList<>();
            Matcher resultMatcher = resultPattern.matcher(body);
            while (resultMatcher.find()) {
                String resultAttrs = resultMatcher.group(1);
                String resultName = getAttribute(resultAttrs, "name");
                String target = compactWhitespace(resultMatcher.group(2));
                results.add(new ResultMapping(resultName == null ? "success" : resultName, target));
            }

            List<String> expressions = new ArrayList<>();
            Matcher expressionMatcher = expressionPattern.matcher(body);
            while (expressionMatcher.find()) {
                expressions.add(expressionMatcher.group(1).trim());
            }

            mappings.add(new ActionMapping(
                    actionName,
                    actionClass,
                    methodExpr,
                    deduplicate(interceptors),
                    results,
                    deduplicate(expressions)
            ));
        }
        return mappings;
    }

    private static List<BeanDependency> extractBeanDependencies(
            String javaText, Path sourceFile, Path beforeRoot, boolean includeBeanFieldFallback
    ) {
        Pattern annotatedFieldPattern = Pattern.compile(
                "(?m)^\\s*@(?:(Autowired|Resource))(?:\\([^)]*\\))?\\s*(?:\\R\\s*)*(?:private|protected|public)\\s+([\\w.$<>?,\\s]+?)\\s+(\\w+)\\s*;"
        );
        Pattern beanFieldPattern = Pattern.compile(
                "(?m)^\\s*(?:private|protected|public)\\s+([\\w.$<>?,\\s]*Bean)\\s+(\\w+)\\s*;"
        );

        String ownerClass = sourceFile.getFileName().toString().replace(".java", "");
        String relPath = beforeRoot.relativize(sourceFile).toString().replace('\\', '/');
        Set<String> seen = new LinkedHashSet<>();
        List<BeanDependency> out = new ArrayList<>();

        Matcher annotatedMatcher = annotatedFieldPattern.matcher(javaText);
        while (annotatedMatcher.find()) {
            String ann = "@" + annotatedMatcher.group(1).trim();
            String type = compactWhitespace(annotatedMatcher.group(2));
            String field = annotatedMatcher.group(3).trim();
            String key = ann + "|" + type + "|" + field;
            if (seen.add(key)) {
                out.add(new BeanDependency(ownerClass, field, type, ann, relPath));
            }
        }

        if (includeBeanFieldFallback) {
            Matcher beanFieldMatcher = beanFieldPattern.matcher(javaText);
            while (beanFieldMatcher.find()) {
                String type = compactWhitespace(beanFieldMatcher.group(1));
                String field = beanFieldMatcher.group(2).trim();
                String key = "<field>|" + type + "|" + field;
                if (seen.add(key)) {
                    out.add(new BeanDependency(ownerClass, field, type, null, relPath));
                }
            }
        }
        return out;
    }

    private static List<ServiceCall> extractServiceCalls(
            String javaText, Path sourceFile, Path beforeRoot, String apiBaseUrl, Map<String, String> properties
    ) {
        Pattern constPattern = Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"\\s*;"
        );
        Pattern urlCallPattern = Pattern.compile("clientSupport\\.getUrl\\(([^)]+)\\)");
        Pattern methodPattern = Pattern.compile("(?m)^\\s*(public|protected|private)\\s+[\\w<>\\[\\], ?]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");

        Map<String, String> constants = new HashMap<>();
        Matcher constMatcher = constPattern.matcher(javaText);
        while (constMatcher.find()) {
            constants.put(constMatcher.group(1).trim(), constMatcher.group(2).trim());
        }

        String serviceClass = sourceFile.getFileName().toString().replace(".java", "");
        String relPath = beforeRoot.relativize(sourceFile).toString().replace('\\', '/');
        List<ServiceCall> out = new ArrayList<>();

        Matcher callMatcher = urlCallPattern.matcher(javaText);
        while (callMatcher.find()) {
            String arg = callMatcher.group(1).trim();
            String propertyKey = resolvePropertyKey(arg, constants);
            String endpointPath = propertyKey == null ? null : properties.get(propertyKey);
            String methodName = findEnclosingMethodName(javaText, callMatcher.start(), methodPattern);
            String resolvedUrl = null;
            if (apiBaseUrl != null && endpointPath != null) {
                resolvedUrl = apiBaseUrl + endpointPath;
            }
            out.add(new ServiceCall(serviceClass, methodName, propertyKey, endpointPath, resolvedUrl, relPath));
        }
        return out;
    }

    private static ControllerEndpoint extractControllerEndpoint(String javaText, Path sourceFile, Path beforeRoot) {
        Pattern classPattern = Pattern.compile("\\bclass\\s+(\\w+)");
        Pattern requestMappingPattern = Pattern.compile("@RequestMapping\\(([^)]*)\\)", Pattern.DOTALL);
        Pattern httpMethodPattern = Pattern.compile("method\\s*=\\s*([A-Z_]+)");

        Matcher classMatcher = classPattern.matcher(javaText);
        if (!classMatcher.find()) {
            return null;
        }
        String controllerClass = classMatcher.group(1).trim();
        int classPos = classMatcher.start();
        String beforeClass = javaText.substring(0, classPos);

        String classLevelRequestMappingArgs = null;
        Matcher mappingMatcher = requestMappingPattern.matcher(beforeClass);
        while (mappingMatcher.find()) {
            classLevelRequestMappingArgs = mappingMatcher.group(1);
        }
        String requestPath = extractQuotedValue(classLevelRequestMappingArgs);
        if (requestPath == null) {
            requestPath = "";
        }

        List<String> httpMethods = new ArrayList<>();
        Matcher methodMatcher = httpMethodPattern.matcher(javaText);
        while (methodMatcher.find()) {
            httpMethods.add(methodMatcher.group(1).trim());
        }

        String relPath = beforeRoot.relativize(sourceFile).toString().replace('\\', '/');
        return new ControllerEndpoint(controllerClass, requestPath, deduplicate(httpMethods), relPath);
    }

    private static String extractServletApiPrefix(String webXml) {
        Pattern mappingPattern = Pattern.compile(
                "<servlet-mapping>\\s*<servlet-name>\\s*dispatcherServlet\\s*</servlet-name>\\s*<url-pattern>\\s*([^<]+)\\s*</url-pattern>",
                Pattern.DOTALL
        );
        Matcher matcher = mappingPattern.matcher(webXml);
        if (!matcher.find()) {
            return "";
        }
        String pattern = matcher.group(1).trim();
        if (pattern.endsWith("/*")) {
            return pattern.substring(0, pattern.length() - 2);
        }
        return pattern;
    }

    private static List<String> extractComponentScans(String xmlText) {
        Pattern scanPattern = Pattern.compile("<context:component-scan[^>]*base-package=\"([^\"]+)\"");
        Matcher matcher = scanPattern.matcher(xmlText);
        List<String> scans = new ArrayList<>();
        while (matcher.find()) {
            scans.add(matcher.group(1).trim());
        }
        return scans;
    }

    private static Map<String, String> loadProperties(Path path) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            out.put(key, value);
        }
        return out;
    }

    private static String resolvePropertyKey(String arg, Map<String, String> constants) {
        String cleaned = arg.replace(")", "").trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            return cleaned.substring(1, cleaned.length() - 1);
        }
        return constants.getOrDefault(cleaned, null);
    }

    private static String findEnclosingMethodName(String text, int position, Pattern methodPattern) {
        Matcher matcher = methodPattern.matcher(text);
        String methodName = null;
        while (matcher.find()) {
            if (matcher.start() > position) {
                break;
            }
            methodName = matcher.group(2).trim();
        }
        return methodName;
    }

    private static void addExistingPath(Map<String, String> target, String key, Path beforeRoot, Path file) {
        if (Files.exists(file)) {
            target.put(key, beforeRoot.relativize(file).toString().replace('\\', '/'));
        }
    }

    private static String getAttribute(String attrs, String key) {
        if (attrs == null) {
            return null;
        }
        Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(attrs);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String extractQuotedValue(String args) {
        if (args == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(args);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String compactWhitespace(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String out = path.trim();
        if (!out.startsWith("/")) {
            out = "/" + out;
        }
        if (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String joinPaths(String prefix, String endpoint) {
        String p = normalizePath(prefix);
        String e = normalizePath(endpoint);
        if (p.isEmpty()) {
            return e;
        }
        if (e.isEmpty()) {
            return p;
        }
        return normalizePath(p + e);
    }

    private static List<String> deduplicate(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    public record RelationManifest(
            String schemaVersion,
            String generatedAt,
            SourceRoots sourceRoots,
            List<ModuleRelation> modules,
            List<CrossModuleRelation> crossModuleRelations
    ) {
    }

    public record SourceRoots(String beforeRoot, String afterRoot) {
    }

    public record ModuleRelation(
            String moduleName,
            String moduleType,
            Map<String, String> configFiles,
            List<String> componentScans,
            List<ActionMapping> actionMappings,
            List<BeanDependency> beanDependencies,
            List<ServiceCall> serviceCalls,
            List<ControllerEndpoint> controllerEndpoints,
            String servletApiPrefix
    ) {
    }

    public record ActionMapping(
            String actionName,
            String actionClass,
            String methodExpression,
            List<String> interceptorRefs,
            List<ResultMapping> results,
            List<String> expressionRefs
    ) {
    }

    public record ResultMapping(String name, String target) {
    }

    public record BeanDependency(
            String ownerClass,
            String fieldName,
            String fieldType,
            String annotation,
            String sourceFile
    ) {
    }

    public record ServiceCall(
            String serviceClass,
            String methodName,
            String propertyKey,
            String endpointPath,
            String resolvedUrl,
            String sourceFile
    ) {
    }

    public record ControllerEndpoint(
            String controllerClass,
            String requestPath,
            List<String> httpMethods,
            String sourceFile
    ) {
    }

    public record CrossModuleRelation(
            String fromModule,
            String serviceClass,
            String methodName,
            String propertyKey,
            String targetEndpointPath,
            String toModule,
            String targetControllerClass,
            String resolvedUrl
    ) {
    }

    private record CliOptions(Path beforeRoot, Path afterRoot, Path reportOut, Path relationOut) {
        static CliOptions parse(String[] args) {
            Path before = null;
            Path after = null;
            Path reportOut = null;
            Path relationOut = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--before" -> before = Path.of(args[++i]).toAbsolutePath();
                    case "--after" -> after = Path.of(args[++i]).toAbsolutePath();
                    case "--report-out" -> reportOut = Path.of(args[++i]).toAbsolutePath();
                    case "--relation-out" -> relationOut = Path.of(args[++i]).toAbsolutePath();
                    default -> {
                    }
                }
            }
            if (before == null) {
                return null;
            }
            if (after == null && reportOut != null) {
                return null;
            }
            return new CliOptions(before, after, reportOut, relationOut);
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
