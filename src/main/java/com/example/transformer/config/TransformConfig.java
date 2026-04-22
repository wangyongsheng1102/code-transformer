package com.example.transformer.config;

import java.util.List;

/**
 * 聚合后的配置对象。
 * 由一个或多个 XML RuleSet（基础规则 / 特定规则）合并而来，
 * 供转换器按既定顺序依次应用。
 */
public class TransformConfig {

    private List<PackageRule> packageRules;
    private List<ImportRule> importRules;
    private List<ClassRule> classRules;
    private List<MethodRule> methodRules;

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

