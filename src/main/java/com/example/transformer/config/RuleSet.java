package com.example.transformer.config;

import java.util.List;

/**
 * 单个 XML 规则集，对应一个 base-rules.xml 或 project-rules.xml。
 */
public class RuleSet {

    /**
     * 规则集名称，仅用于说明。
     */
    private String name;

    /**
     * 规则集类型：BASE / PROJECT。
     */
    private RuleSetType type = RuleSetType.BASE;

    private List<PackageRule> packageRules;
    private List<ImportRule> importRules;
    private List<ClassRule> classRules;
    private List<MethodRule> methodRules;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public RuleSetType getType() {
        return type;
    }

    public void setType(RuleSetType type) {
        this.type = type;
    }

    public List<PackageRule> getPackageRules() {
        return packageRules;
    }

    public void setPackageRules(List<PackageRule> packageRules) {
        this.packageRules = packageRules;
    }

    public List<ImportRule> getImportRules() {
        return importRules;
    }

    public void setImportRules(List<ImportRule> importRules) {
        this.importRules = importRules;
    }

    public List<ClassRule> getClassRules() {
        return classRules;
    }

    public void setClassRules(List<ClassRule> classRules) {
        this.classRules = classRules;
    }

    public List<MethodRule> getMethodRules() {
        return methodRules;
    }

    public void setMethodRules(List<MethodRule> methodRules) {
        this.methodRules = methodRules;
    }
}

