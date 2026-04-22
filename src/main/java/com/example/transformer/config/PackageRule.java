package com.example.transformer.config;

/**
 * 包名转换规则：支持前缀匹配，将旧包名重写为新包名。
 */
public class PackageRule {

    /**
     * 是否启用该规则，默认启用。
     */
    private boolean enabled = true;

    /**
     * 规则优先级，数值越小越先执行。
     */
    private int priority = 100;

    /**
     * 要匹配的包名前缀，例如：com.foo.project.struts.action
     */
    private String matchPrefix;

    /**
     * 目标包名前缀，例如：com.foo.project.web.controller
     */
    private String targetPrefix;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getMatchPrefix() {
        return matchPrefix;
    }

    public void setMatchPrefix(String matchPrefix) {
        this.matchPrefix = matchPrefix;
    }

    public String getTargetPrefix() {
        return targetPrefix;
    }

    public void setTargetPrefix(String targetPrefix) {
        this.targetPrefix = targetPrefix;
    }
}

