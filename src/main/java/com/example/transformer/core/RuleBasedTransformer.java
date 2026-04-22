package com.example.transformer.core;

import com.example.transformer.config.ClassRule;
import com.example.transformer.config.ImportRule;
import com.example.transformer.config.MethodAction;
import com.example.transformer.config.MethodRule;
import com.example.transformer.config.NewMethodSpec;
import com.example.transformer.config.PackageRule;
import com.example.transformer.config.TextReplaceRule;
import com.example.transformer.config.TransformConfig;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.body.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/**
 * 基于配置规则的共通性转换器。
 * 当前版本专注于：
 * - package 声明重写
 * - import 增删改
 * - 类级别注解添加、继承/实现移除
 */
public class RuleBasedTransformer {

    private final TransformConfig config;

    public RuleBasedTransformer(TransformConfig config) {
        this.config = config;
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    }

    /**
     * 转换单个 Java 源文件。
     */
    public void transformFile(Path sourceFile, Path outputRoot) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(sourceFile);

        applyPackageRules(cu);
        applyImportRules(cu);
        applyClassRules(cu);
        applyMethodRules(cu);

        // 计算输出路径（基于 package）
        String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getName().toString())
                .orElse("");
        Path pkgDir = pkg.isEmpty()
                ? outputRoot
                : outputRoot.resolve(pkg.replace('.', '/'));
        Files.createDirectories(pkgDir);

        Path target = pkgDir.resolve(sourceFile.getFileName().toString());
        Files.writeString(target, cu.toString(), StandardCharsets.UTF_8);
    }

    private void applyPackageRules(CompilationUnit cu) {
        List<PackageRule> rules = config.getPackageRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }
        Optional<String> currentPkgOpt = cu.getPackageDeclaration()
                .map(pd -> pd.getName().toString());
        if (currentPkgOpt.isEmpty()) {
            return;
        }
        String currentPkg = currentPkgOpt.get();
        for (PackageRule rule : rules) {
            String match = rule.getMatchPrefix();
            String target = rule.getTargetPrefix();
            if (match != null && target != null && currentPkg.startsWith(match)) {
                String newPkg = target + currentPkg.substring(match.length());
                cu.setPackageDeclaration(newPkg);
            }
        }
    }

    private void applyImportRules(CompilationUnit cu) {
        List<ImportRule> rules = config.getImportRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }
        cu.getImports().removeIf(importDecl -> {
            String name = importDecl.getNameAsString();
            for (ImportRule rule : rules) {
                String match = rule.getMatch();
                if (match == null || match.isEmpty()) {
                    continue;
                }
                if (name.equals(match) || name.startsWith(match)) {
                    if (rule.getAction() == ImportRule.ActionType.REMOVE) {
                        return true;
                    } else if (rule.getAction() == ImportRule.ActionType.REPLACE) {
                        if (rule.getReplacement() != null && !rule.getReplacement().isEmpty()) {
                            importDecl.setName(rule.getReplacement());
                        } else if (rule.getReplacementPrefix() != null && !rule.getReplacementPrefix().isEmpty()) {
                            String replacement = rule.getReplacementPrefix() + name.substring(match.length());
                            importDecl.setName(replacement);
                        }
                        return false;
                    } else if (rule.getAction() == ImportRule.ActionType.ADD) {
                        // ADD 类型不通过 removeIf 处理，在后续统一 ensureImport
                    }
                }
            }
            return false;
        });

        // 处理 ADD 类型：确保指定 import 存在
        for (ImportRule rule : rules) {
            if (rule.getAction() == ImportRule.ActionType.ADD) {
                String match = rule.getMatch();
                if (match != null && !match.isEmpty()) {
                    ensureImport(cu, match);
                }
            }
        }
    }

    private void applyClassRules(CompilationUnit cu) {
        List<ClassRule> rules = config.getClassRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }
        cu.accept(new ModifierVisitor<Void>() {
            @Override
            public ClassOrInterfaceDeclaration visit(ClassOrInterfaceDeclaration n, Void arg) {
                super.visit(n, arg);
                ClassOrInterfaceDeclaration decl = n;

                for (ClassRule rule : rules) {
                    if (!rule.isEnabled()) {
                        continue;
                    }
                    if (!matchesClassRule(decl, rule)) {
                        continue;
                    }
                    if (rule.isRemoveExtends()) {
                        if (rule.getExtendsClass() != null && !rule.getExtendsClass().isEmpty()) {
                            String extendsSimple = simpleName(rule.getExtendsClass());
                            decl.getExtendedTypes().removeIf(t -> t.getNameAsString().equals(extendsSimple));
                        } else {
                            decl.getExtendedTypes().clear();
                        }
                    }
                    if (rule.getReplaceExtends() != null && !rule.getReplaceExtends().isEmpty()) {
                        if (rule.getExtendsClass() != null && !rule.getExtendsClass().isEmpty()) {
                            String extendsSimple = simpleName(rule.getExtendsClass());
                            decl.getExtendedTypes().removeIf(t -> t.getNameAsString().equals(extendsSimple));
                        } else {
                            decl.getExtendedTypes().clear();
                        }
                        String replaceSimple = simpleName(rule.getReplaceExtends());
                        boolean alreadyExists = decl.getExtendedTypes().stream()
                                .anyMatch(t -> t.getNameAsString().equals(replaceSimple));
                        if (!alreadyExists) {
                            decl.getExtendedTypes().add(new ClassOrInterfaceType(null, replaceSimple));
                            if (rule.getReplaceExtends().contains(".")) {
                                ensureImport(cu, rule.getReplaceExtends());
                            }
                        }
                    }
                    if (rule.getRemoveImplements() != null && !rule.getRemoveImplements().isEmpty()) {
                        decl.getImplementedTypes().removeIf(t ->
                                rule.getRemoveImplements().stream()
                                        .map(RuleBasedTransformer.this::simpleName)
                                        .anyMatch(simple -> simple.equals(t.getNameAsString()))
                        );
                    }
                    if (rule.getAddImplements() != null && !rule.getAddImplements().isEmpty()) {
                        for (String impl : rule.getAddImplements()) {
                            String implSimple = simpleName(impl);
                            boolean exists = decl.getImplementedTypes().stream()
                                    .anyMatch(t -> t.getNameAsString().equals(implSimple));
                            if (!exists) {
                                decl.getImplementedTypes().add(new ClassOrInterfaceType(null, implSimple));
                                if (impl.contains(".")) {
                                    ensureImport(cu, impl);
                                }
                            }
                        }
                    }
                    if (rule.getAddAnnotations() != null) {
                        for (String annFqn : rule.getAddAnnotations()) {
                            String simpleName = annFqn.substring(annFqn.lastIndexOf('.') + 1);
                            boolean exists = decl.getAnnotations().stream()
                                    .anyMatch(a -> a.getName().getIdentifier().equals(simpleName));
                            if (!exists) {
                                decl.addAnnotation(new MarkerAnnotationExpr(simpleName));
                                // 确保有对应的 import
                                ensureImport(cu, annFqn);
                            }
                        }
                    }
                }

                return decl;
            }
        }, null);
    }

    private void applyMethodRules(CompilationUnit cu) {
        List<MethodRule> rules = config.getMethodRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }

        cu.accept(new ModifierVisitor<Void>() {
            @Override
            public ClassOrInterfaceDeclaration visit(ClassOrInterfaceDeclaration n, Void arg) {
                super.visit(n, arg);
                ClassOrInterfaceDeclaration decl = n;
                String className = decl.getNameAsString();

                // 删除与替换
                for (MethodRule rule : rules) {
                    if (!rule.isEnabled()) {
                        continue;
                    }
                    if (!matchesTargetClass(className, rule.getTargetClassPattern())) {
                        continue;
                    }
                    MethodAction action = rule.getAction();
                    if (action == MethodAction.DELETE || action == MethodAction.REPLACE) {
                        List<MethodDeclaration> toRemove = decl.getMethods().stream()
                                .filter(m -> matchesMethod(m, rule))
                                .toList();
                        toRemove.forEach(decl::remove);

                        if (action == MethodAction.REPLACE && rule.getNewMethod() != null) {
                            addNewMethod(decl, cu, className, rule.getNewMethod());
                        }
                    }
                }

                // 在原方法上修改
                for (MethodRule rule : rules) {
                    if (!rule.isEnabled() || rule.getAction() != MethodAction.MODIFY) {
                        continue;
                    }
                    if (!matchesTargetClass(className, rule.getTargetClassPattern())) {
                        continue;
                    }
                    decl.getMethods().stream()
                            .filter(m -> matchesMethod(m, rule))
                            .forEach(m -> applyModifyRule(cu, m, rule));
                }

                // 纯新增
                for (MethodRule rule : rules) {
                    if (!rule.isEnabled() || rule.getAction() != MethodAction.ADD) {
                        continue;
                    }
                    NewMethodSpec spec = rule.getNewMethod();
                    if (spec == null || spec.getName() == null || spec.getName().isEmpty()) {
                        continue;
                    }
                    if (!matchesTargetClass(className, rule.getTargetClassPattern())) {
                        continue;
                    }
                    boolean exists = decl.getMethods().stream()
                            .anyMatch(m -> m.getNameAsString().equals(spec.getName()));
                    if (!exists) {
                        addNewMethod(decl, cu, className, spec);
                    }
                }

                return decl;
            }
        }, null);
    }

    private boolean matchesMethod(MethodDeclaration method, MethodRule rule) {
        if (rule.getName() != null && !rule.getName().isEmpty()) {
            if (!method.getNameAsString().equals(rule.getName())) {
                return false;
            }
        }
        if (rule.getReturnType() != null && !rule.getReturnType().isEmpty()) {
            String rt = method.getType().asString();
            if (!rt.equals(rule.getReturnType()) && !rt.endsWith(simpleName(rule.getReturnType()))) {
                return false;
            }
        }
        if (rule.getMatchAnnotations() != null && !rule.getMatchAnnotations().isEmpty()) {
            Set<String> methodAnnotations = new HashSet<>();
            method.getAnnotations().forEach(ann -> methodAnnotations.add(ann.getName().getIdentifier()));
            boolean matched = rule.getMatchAnnotations().stream()
                    .map(this::simpleName)
                    .anyMatch(methodAnnotations::contains);
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private void applyModifyRule(CompilationUnit cu, MethodDeclaration method, MethodRule rule) {
        if (rule.getRenameTo() != null && !rule.getRenameTo().isEmpty()) {
            method.setName(rule.getRenameTo());
        }

        if (rule.getAddAnnotations() != null) {
            for (String annFqn : rule.getAddAnnotations()) {
                String simple = simpleName(annFqn);
                boolean exists = method.getAnnotations().stream()
                        .anyMatch(a -> a.getName().getIdentifier().equals(simple));
                if (!exists) {
                    method.addAnnotation(simple);
                    ensureImport(cu, annFqn);
                }
            }
        }

        if (rule.getAddParameters() != null) {
            for (String paramSpec : rule.getAddParameters()) {
                addParameterIfAbsent(cu, method, paramSpec);
            }
        }

        if (rule.getBodyTextReplacements() != null && method.getBody().isPresent()) {
            String body = method.getBody().get().toString();
            for (TextReplaceRule replacement : rule.getBodyTextReplacements()) {
                if (replacement == null || replacement.getFind() == null || replacement.getFind().isEmpty()) {
                    continue;
                }
                String to = replacement.getReplace() == null ? "" : replacement.getReplace();
                if (replacement.isRegex()) {
                    body = body.replaceAll(replacement.getFind(), to);
                } else {
                    body = body.replace(replacement.getFind(), to);
                }
            }
            method.setBody(StaticJavaParser.parseBlock(body));
        }
    }

    private void addNewMethod(ClassOrInterfaceDeclaration decl,
                              CompilationUnit cu,
                              String className,
                              NewMethodSpec spec) {
        String methodName = spec.getName();
        if (methodName == null || methodName.isEmpty()) {
            return;
        }
        MethodDeclaration m = decl.addMethod(methodName, Modifier.Keyword.PUBLIC);

        if (spec.getReturnType() != null && !spec.getReturnType().isEmpty()) {
            m.setType(spec.getReturnType());
        } else {
            m.setType("void");
        }

        if (spec.getAnnotations() != null) {
            for (String annFqn : spec.getAnnotations()) {
                String simple = simpleName(annFqn);
                boolean exists = m.getAnnotations().stream()
                        .anyMatch(a -> a.getName().getIdentifier().equals(simple));
                if (!exists) {
                    m.addAnnotation(simple);
                    ensureImport(cu, annFqn);
                }
            }
        }

        if (spec.getParameters() != null) {
            for (String paramSpec : spec.getParameters()) {
                addParameterIfAbsent(cu, m, paramSpec);
            }
        }

        if (spec.getBodyTemplate() != null && !spec.getBodyTemplate().isEmpty()) {
            String rawBody = spec.getBodyTemplate().trim();
            if (!rawBody.startsWith("{")) {
                rawBody = "{\n" + rawBody + "\n}";
            }
            m.setBody(StaticJavaParser.parseBlock(rawBody));
        } else {
            BlockStmt body = new BlockStmt();
            body.addOrphanComment(new LineComment("TODO auto-generated by transformer for class " + className));
            m.setBody(body);
        }
    }

    private boolean matchesTargetClass(String className, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        return className.matches(pattern);
    }

    private void addParameterIfAbsent(CompilationUnit cu, MethodDeclaration method, String paramSpec) {
        if (paramSpec == null || paramSpec.isBlank()) {
            return;
        }
        String[] parts = paramSpec.trim().split("\\s+");
        if (parts.length < 2) {
            return;
        }
        String paramName = parts[parts.length - 1];
        String typeName = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
        boolean exists = method.getParameters().stream()
                .anyMatch(p -> p.getNameAsString().equals(paramName));
        if (!exists) {
            method.addParameter(new Parameter(StaticJavaParser.parseType(typeName), new NameExpr(paramName).getName()));
            ensureImport(cu, typeName);
        }
    }

    private boolean matchesClassRule(ClassOrInterfaceDeclaration decl, ClassRule rule) {
        if (rule.getExtendsClass() != null) {
            boolean matchExtends = decl.getExtendedTypes().stream()
                    .anyMatch(t -> t.getNameAsString().equals(simpleName(rule.getExtendsClass())));
            if (!matchExtends) {
                return false;
            }
        }
        if (rule.getImplementsInterfaces() != null && !rule.getImplementsInterfaces().isEmpty()) {
            boolean anyMatch = decl.getImplementedTypes().stream().anyMatch(t -> {
                String simple = t.getNameAsString();
                return rule.getImplementsInterfaces().stream()
                        .map(this::simpleName)
                        .anyMatch(simple::equals);
            });
            if (!anyMatch) {
                return false;
            }
        }
        return true;
    }

    private String simpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return fqn;
        }
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    private void ensureImport(CompilationUnit cu, String fqn) {
        String normalized = fqn == null ? "" : fqn.trim();
        if (normalized.isEmpty() || !normalized.contains(".")) {
            return;
        }
        boolean exists = cu.getImports().stream()
                .anyMatch(id -> id.getNameAsString().equals(normalized));
        if (!exists) {
            cu.addImport(normalized);
        }
        // 也要确保不会有同名冲突，这里先简单实现：不解决冲突，仅添加 import。
    }
}

