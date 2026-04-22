package com.example.transformer.cli;

import com.example.transformer.config.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
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
        mapper.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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

        List<RuleSet> orderedSets = sets.stream()
                .sorted(Comparator.comparingInt(set -> extractTypeOrder(set.getType())))
                .toList();

        List<PackageRule> allPackage = new ArrayList<>();
        List<ImportRule> allImport = new ArrayList<>();
        List<ClassRule> allClass = new ArrayList<>();
        List<MethodRule> allMethod = new ArrayList<>();

        for (RuleSet set : orderedSets) {
            Optional.ofNullable(set.getPackageRules()).orElse(List.of()).stream()
                    .filter(PackageRule::isEnabled)
                    .sorted(Comparator.comparingInt(PackageRule::getPriority))
                    .forEach(allPackage::add);

            Optional.ofNullable(set.getImportRules()).orElse(List.of()).stream()
                    .filter(ImportRule::isEnabled)
                    .sorted(Comparator.comparingInt(ImportRule::getPriority))
                    .forEach(allImport::add);

            Optional.ofNullable(set.getClassRules()).orElse(List.of()).stream()
                    .filter(ClassRule::isEnabled)
                    .sorted(Comparator.comparingInt(ClassRule::getPriority))
                    .forEach(allClass::add);

            Optional.ofNullable(set.getMethodRules()).orElse(List.of()).stream()
                    .filter(MethodRule::isEnabled)
                    .sorted(Comparator.comparingInt(MethodRule::getPriority))
                    .forEach(allMethod::add);
        }

        config.setPackageRules(allPackage);
        config.setImportRules(allImport);
        config.setClassRules(allClass);
        config.setMethodRules(allMethod);

        return config;
    }

    private static int extractTypeOrder(RuleSetType type) {
        return type == RuleSetType.BASE ? 0 : 1;
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


