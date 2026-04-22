package com.example.transformer.config;

/**
 * import 增删改规则。
 */
public class ImportRule {

    public enum ActionType {
        REMOVE,
        REPLACE,
        ADD
    }

    /**
     * 是否启用该规则，默认启用。
     */
    private boolean enabled = true;

    /**
     * 规则优先级，数值越小越先执行。
     */
    private int priority = 100;

    /**
     * 要匹配的 import 完整类名或通配前缀，例如：
     * - com.opensymphony.xwork2.ActionSupport
     * - com.opensymphony.xwork2.
     */
    private String match;

    /**
     * 动作类型：REMOVE / REPLACE。
     */
    private ActionType action;

    /**
     * 当 action=REPLACE 时，替换为的新 import。
     */
    private String replacement;

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

    public String getMatch() {
        return match;
    }

    public void setMatch(String match) {
        this.match = match;
    }

    public ActionType getAction() {
        return action;
    }

    public void setAction(ActionType action) {
        this.action = action;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }
}

