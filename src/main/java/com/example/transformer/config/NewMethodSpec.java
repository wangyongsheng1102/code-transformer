package com.example.transformer.config;

import java.util.List;

/**
 * 用于描述新增/替代方法的结构。
 */
public class NewMethodSpec {

    /**
     * 方法名。
     */
    private String name;

    /**
     * 返回类型（完整类名或简单类型）。
     */
    private String returnType;

    /**
     * 需要增加的注解（完整类名）。
     */
    private List<String> annotations;

    /**
     * 方法体模板，可以是任意字符串，实际生成时作为注释或占位代码。
     */
    private String bodyTemplate;

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

    public List<String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }
}

