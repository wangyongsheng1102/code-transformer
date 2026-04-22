package com.example.transformer.cli;

import com.example.transformer.config.*;
import com.example.transformer.core.RuleBasedTransformer;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 命令行入口：
 * java -jar transformer.jar --source /path/to/src --target /path/to/out --base-config base-rules.xml [--project-config project-rules.xml]
 */
public class TransformerCli {

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        if (options == null) {
            printUsage();
            return;
        }

        List<RuleSet> ruleSets = new ArrayList<>();
        if (options.baseConfig() != null) {
            ruleSets.add(loadRuleSet(options.baseConfig(), RuleSetType.BASE));
        }
        if (options.projectConfig() != null) {
            ruleSets.add(loadRuleSet(options.projectConfig(), RuleSetType.PROJECT));
        }
        if (ruleSets.isEmpty()) {
            System.err.println("No rules loaded. Please provide at least --base-config or --project-config.");
            printUsage();
            return;
        }

        TransformConfig config = mergeRuleSets(ruleSets);

        RuleBasedTransformer transformer = new RuleBasedTransformer(config);
        Files.walk(options.sourceRoot())
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        System.out.println("Transforming: " + p);
                        transformer.transformFile(p, options.targetRoot());
                    } catch (IOException e) {
                        System.err.println("Failed to transform " + p + ": " + e.getMessage());
                    }
                });
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar transformer.jar --source <srcRoot> --target <outRoot> "
                + "[--base-config <base-rules.xml>] [--project-config <project-rules.xml>]");
    }

    private static RuleSet loadRuleSet(Path configPath, RuleSetType fallbackType) throws IOException {
        XmlMapper mapper = new XmlMapper();
        RuleSet set = mapper.readValue(configPath.toFile(), RuleSet.class);
        if (set.getType() == null) {
            set.setType(fallbackType);
        }
        if (set.getName() == null || set.getName().isEmpty()) {
            set.setName(configPath.getFileName().toString());
        }
        System.out.println("Loaded RuleSet: " + set.getName() + " (type=" + set.getType() + ")");
        return set;
    }

    /**
     * 将多个 RuleSet（基础 + 特定）合并为一个 TransformConfig，并按 type + priority 排序。
     */
    private static TransformConfig mergeRuleSets(List<RuleSet> sets) {
        TransformConfig config = new TransformConfig();

        Comparator<Object> byPriority = (a, b) -> {
            int pa = extractPriority(a);
            int pb = extractPriority(b);
            return Integer.compare(pa, pb);
        };

        List<PackageRule> allPackage = sets.stream()
                .flatMap(set -> Optional.ofNullable(set.getPackageRules()).orElse(List.of()).stream()
                        .filter(PackageRule::isEnabled)
                        .map(r -> tagRuleType(r, set.getType())))
                .sorted((a, b) -> {
                    int ta = extractTypeOrder(a);
                    int tb = extractTypeOrder(b);
                    if (ta != tb) {
                        return Integer.compare(ta, tb);
                    }
                    return byPriority.compare(a, b);
                })
                .map(r -> (PackageRule) r)
                .collect(Collectors.toList());

        List<ImportRule> allImport = sets.stream()
                .flatMap(set -> Optional.ofNullable(set.getImportRules()).orElse(List.of()).stream()
                        .filter(ImportRule::isEnabled)
                        .map(r -> tagRuleType(r, set.getType())))
                .sorted((a, b) -> {
                    int ta = extractTypeOrder(a);
                    int tb = extractTypeOrder(b);
                    if (ta != tb) {
                        return Integer.compare(ta, tb);
                    }
                    return byPriority.compare(a, b);
                })
                .map(r -> (ImportRule) r)
                .collect(Collectors.toList());

        List<ClassRule> allClass = sets.stream()
                .flatMap(set -> Optional.ofNullable(set.getClassRules()).orElse(List.of()).stream()
                        .filter(ClassRule::isEnabled)
                        .map(r -> tagRuleType(r, set.getType())))
                .sorted((a, b) -> {
                    int ta = extractTypeOrder(a);
                    int tb = extractTypeOrder(b);
                    if (ta != tb) {
                        return Integer.compare(ta, tb);
                    }
                    return byPriority.compare(a, b);
                })
                .map(r -> (ClassRule) r)
                .collect(Collectors.toList());

        List<MethodRule> allMethod = sets.stream()
                .flatMap(set -> Optional.ofNullable(set.getMethodRules()).orElse(List.of()).stream()
                        .filter(MethodRule::isEnabled)
                        .map(r -> tagRuleType(r, set.getType())))
                .sorted((a, b) -> {
                    int ta = extractTypeOrder(a);
                    int tb = extractTypeOrder(b);
                    if (ta != tb) {
                        return Integer.compare(ta, tb);
                    }
                    return byPriority.compare(a, b);
                })
                .map(r -> (MethodRule) r)
                .collect(Collectors.toList());

        config.setPackageRules(allPackage);
        config.setImportRules(allImport);
        config.setClassRules(allClass);
        config.setMethodRules(allMethod);

        return config;
    }

    /**
     * 为规则打上“来源 RuleSet 类型”的临时标签（通过内部类包装），以便排序时识别 BASE/PROJECT。
     * 这里采用简单实现：包装成匿名对象，带上一个 type 字段。
     */
    private static Object tagRuleType(Object rule, RuleSetType type) {
        return new Object() {
            final Object r = rule;
            final RuleSetType t = type;

            @Override
            public String toString() {
                return r.toString();
            }
        };
    }

    private static int extractPriority(Object taggedRule) {
        Object r = unwrap(taggedRule);
        if (r instanceof PackageRule pr) {
            return pr.getPriority();
        }
        if (r instanceof ImportRule ir) {
            return ir.getPriority();
        }
        if (r instanceof ClassRule cr) {
            return cr.getPriority();
        }
        if (r instanceof MethodRule mr) {
            return mr.getPriority();
        }
        return 100;
    }

    private static int extractTypeOrder(Object taggedRule) {
        if (taggedRule.getClass().isAnonymousClass()) {
            try {
                var field = taggedRule.getClass().getDeclaredField("t");
                field.setAccessible(true);
                RuleSetType type = (RuleSetType) field.get(taggedRule);
                return type == RuleSetType.BASE ? 0 : 1;
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    private static Object unwrap(Object taggedRule) {
        if (taggedRule.getClass().isAnonymousClass()) {
            try {
                var field = taggedRule.getClass().getDeclaredField("r");
                field.setAccessible(true);
                return field.get(taggedRule);
            } catch (Exception ignored) {
            }
        }
        return taggedRule;
    }

    /**
     * 简单的命令行参数解析。
     */
    private record CliOptions(Path sourceRoot, Path targetRoot, Path baseConfig, Path projectConfig) {

        static CliOptions parse(String[] args) {
            Path src = null;
            Path dst = null;
            Path base = null;
            Path project = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source" -> src = Paths.get(args[++i]);
                    case "--target" -> dst = Paths.get(args[++i]);
                    case "--base-config" -> base = Paths.get(args[++i]);
                    case "--project-config" -> project = Paths.get(args[++i]);
                    default -> {
                    }
                }
            }
            if (src == null || dst == null) {
                return null;
            }
            return new CliOptions(
                    src.toAbsolutePath(),
                    dst.toAbsolutePath(),
                    base == null ? null : base.toAbsolutePath(),
                    project == null ? null : project.toAbsolutePath()
            );
        }
    }
}


