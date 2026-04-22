package com.example.transformer.config;

import java.util.List;

/**
 * 方法级别转换规则（共通功能先支持：按名称/返回类型匹配，增加注解）。
 */
public class MethodRule {

    /**
     * 是否启用该规则，默认启用。
     */
    private boolean enabled = true;

    /**
     * 规则优先级，数值越小越先执行。
     */
    private int priority = 100;

    /**
     * 规则动作：新增 / 删除 / 替换。
     */
    private MethodAction action = MethodAction.REPLACE;

    /**
     * 匹配的方法名（可选）。
     */
    private String name;

    /**
     * 匹配的返回类型（完整类名，可选），例如：java.lang.String。
     */
    private String returnType;

    /**
     * 针对 ADD 类型规则：匹配的目标类名模式（正则），为空则对所有类生效。
     */
    private String targetClassPattern;

    /**
     * 新方法描述（用于 ADD / REPLACE）。
     */
    private NewMethodSpec newMethod;

    /**
     * 需要增加的注解（完整类名），例如：
     * - org.springframework.web.bind.annotation.GetMapping
     * - org.springframework.web.bind.annotation.PostMapping
     */
    private List<String> addAnnotations;
    /**
     * 匹配的方法注解（简单名或全限定名），为空则不按注解过滤。
     */
    private List<String> matchAnnotations;

    /**
     * 仅对匹配到的方法增加参数，格式：\"类型 变量名\"，例如：
     * - org.springframework.ui.ModelMap modelMap
     * - jakarta.servlet.http.HttpServletRequest request
     */
    private List<String> addParameters;

    /**
     * 方法名重命名（仅 MODIFY 生效），为空则不重命名。
     */
    private String renameTo;

    /**
     * 方法体文本替换规则（仅 MODIFY 生效）。
     */
    private List<TextReplaceRule> bodyTextReplacements;

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

    public MethodAction getAction() {
        return action;
    }

    public void setAction(MethodAction action) {
        this.action = action;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public String getTargetClassPattern() {
        return targetClassPattern;
    }

    public void setTargetClassPattern(String targetClassPattern) {
        this.targetClassPattern = targetClassPattern;
    }

    public NewMethodSpec getNewMethod() {
        return newMethod;
    }

    public void setNewMethod(NewMethodSpec newMethod) {
        this.newMethod = newMethod;
    }

    public List<String> getAddAnnotations() {
        return addAnnotations;
    }

    public void setAddAnnotations(List<String> addAnnotations) {
        this.addAnnotations = addAnnotations;
    }

    public List<String> getMatchAnnotations() {
        return matchAnnotations;
    }

    public void setMatchAnnotations(List<String> matchAnnotations) {
        this.matchAnnotations = matchAnnotations;
    }

    public List<String> getAddParameters() {
        return addParameters;
    }

    public void setAddParameters(List<String> addParameters) {
        this.addParameters = addParameters;
    }

    public String getRenameTo() {
        return renameTo;
    }

    public void setRenameTo(String renameTo) {
        this.renameTo = renameTo;
    }

    public List<TextReplaceRule> getBodyTextReplacements() {
        return bodyTextReplacements;
    }

    public void setBodyTextReplacements(List<TextReplaceRule> bodyTextReplacements) {
        this.bodyTextReplacements = bodyTextReplacements;
    }
}

