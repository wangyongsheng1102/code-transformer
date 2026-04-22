package com.example.transformer.config;

import java.util.List;

/**
 * 新增字段定义（用于 ClassRule 的 AddFields）。
 */
public class NewFieldSpec {

    /**
     * 字段名。
     */
    private String name;

    /**
     * 字段类型（可为完整类名）。
     */
    private String type;

    /**
     * 字段修饰符，默认 private，可选：public/protected/private。
     */
    private String modifier = "private";

    /**
     * 字段注解（完整类名）。
     */
    private List<String> annotations;

    /**
     * 初始化表达式（可选），例如：
     * - new HashMap<>()
     * - "default"
     */
    private String initializer;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getModifier() {
        return modifier;
    }

    public void setModifier(String modifier) {
        this.modifier = modifier;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
    }

    public String getInitializer() {
        return initializer;
    }

    public void setInitializer(String initializer) {
        this.initializer = initializer;
    }
}
