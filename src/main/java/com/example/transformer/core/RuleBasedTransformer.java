package com.example.transformer.core;

import com.example.transformer.config.ClassRule;
import com.example.transformer.config.ImportRule;
import com.example.transformer.config.MethodAction;
import com.example.transformer.config.MethodRule;
import com.example.transformer.config.NewMethodSpec;
import com.example.transformer.config.PackageRule;
import com.example.transformer.config.TransformConfig;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.visitor.ModifierVisitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
                        decl.getExtendedTypes().clear();
                    }
                    if (rule.getRemoveImplements() != null && !rule.getRemoveImplements().isEmpty()) {
                        decl.getImplementedTypes().removeIf(t ->
                                rule.getRemoveImplements().contains(t.getNameAsString())
                        );
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

                // 纯新增
                for (MethodRule rule : rules) {
                    if (!rule.isEnabled() || rule.getAction() != MethodAction.ADD) {
                        continue;
                    }
                    NewMethodSpec spec = rule.getNewMethod();
                    if (spec == null || spec.getName() == null || spec.getName().isEmpty()) {
                        continue;
                    }
                    String pattern = rule.getTargetClassPattern();
                    if (pattern != null && !pattern.isEmpty() && !className.matches(pattern)) {
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
        return true;
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

        BlockStmt body = new BlockStmt();
        body.addOrphanComment(new LineComment("TODO auto-generated by transformer for class " + className));
        if (spec.getBodyTemplate() != null && !spec.getBodyTemplate().isEmpty()) {
            body.addOrphanComment(new BlockComment(spec.getBodyTemplate()));
        }
        m.setBody(body);
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
        int idx = fqn.lastIndexOf('.');
        return idx >= 0 ? fqn.substring(idx + 1) : fqn;
    }

    private void ensureImport(CompilationUnit cu, String fqn) {
        String simpleName = simpleName(fqn);
        boolean exists = cu.getImports().stream()
                .anyMatch(id -> id.getNameAsString().equals(fqn));
        if (!exists) {
            cu.addImport(new ImportDeclaration(new Name(fqn), false, false));
        }
        // 也要确保不会有同名冲突，这里先简单实现：不解决冲突，仅添加 import。
    }
}

