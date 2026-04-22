package com.example.transformer.config;

import java.util.List;

/**
 * 类级别转换规则，例如：
 * - 识别 extends ActionSupport 的类
 * - 移除继承/实现
 * - 增加 @Controller/@RestController 注解
 */
public class ClassRule {

    /**
     * 是否启用该规则，默认启用。
     */
    private boolean enabled = true;

    /**
     * 规则优先级，数值越小越先执行。
     */
    private int priority = 100;

    /**
     * 匹配条件：父类全名，可选。
     */
    private String extendsClass;

    /**
     * 匹配条件：实现的接口全名列表，可选。
     */
    private List<String> implementsInterfaces;

    /**
     * 是否移除父类。
     */
    private boolean removeExtends;

    /**
     * 替换父类（完整类名或简单类名），为空则不替换。
     * 例如：BaseController / com.foo.web.BaseController
     */
    private String replaceExtends;

    /**
     * 需要移除的接口列表。
     */
    private List<String> removeImplements;

    /**
     * 需要新增的实现接口列表（完整类名或简单类名），为空则不新增。
     * 例如：org.springframework.web.servlet.HandlerInterceptor
     */
    private List<String> addImplements;

    /**
     * 需要增加的注解（完整类名），例如：
     * - org.springframework.stereotype.Controller
     * - org.springframework.web.bind.annotation.RestController
     */
    private List<String> addAnnotations;

    /**
     * 需要新增的字段（类级别基础设施注入），为空则不新增。
     */
    private List<NewFieldSpec> addFields;

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

    public String getExtendsClass() {
        return extendsClass;
    }

    public void setExtendsClass(String extendsClass) {
        this.extendsClass = extendsClass;
    }

    public List<String> getImplementsInterfaces() {
        return implementsInterfaces;
    }

    public void setImplementsInterfaces(List<String> implementsInterfaces) {
        this.implementsInterfaces = implementsInterfaces;
    }

    public boolean isRemoveExtends() {
        return removeExtends;
    }

    public void setRemoveExtends(boolean removeExtends) {
        this.removeExtends = removeExtends;
    }

    public String getReplaceExtends() {
        return replaceExtends;
    }

    public void setReplaceExtends(String replaceExtends) {
        this.replaceExtends = replaceExtends;
    }

    public List<String> getRemoveImplements() {
        return removeImplements;
    }

    public void setRemoveImplements(List<String> removeImplements) {
        this.removeImplements = removeImplements;
    }

    public List<String> getAddImplements() {
        return addImplements;
    }

    public void setAddImplements(List<String> addImplements) {
        this.addImplements = addImplements;
    }

    public List<String> getAddAnnotations() {
        return addAnnotations;
    }

    public void setAddAnnotations(List<String> addAnnotations) {
        this.addAnnotations = addAnnotations;
    }

    public List<NewFieldSpec> getAddFields() {
        return addFields;
    }

    public void setAddFields(List<NewFieldSpec> addFields) {
        this.addFields = addFields;
    }
}

