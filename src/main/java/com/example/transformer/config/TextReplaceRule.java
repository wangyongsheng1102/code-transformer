package com.example.transformer.config;

/**
 * 方法体文本替换规则。
 */
public class TextReplaceRule {

    /**
     * 查找文本。
     */
    private String find;

    /**
     * 替换文本。
     */
    private String replace;

    /**
     * 是否启用正则替换。
     */
    private boolean regex;

    public String getFind() {
        return find;
    }

    public void setFind(String find) {
        this.find = find;
    }

    public String getReplace() {
        return replace;
    }

    public void setReplace(String replace) {
        this.replace = replace;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }

    /**
     * 兼容旧字段名：From。
     */
    public String getFrom() {
        return find;
    }

    /**
     * 兼容旧字段名：From。
     */
    public void setFrom(String from) {
        this.find = from;
    }

    /**
     * 兼容旧字段名：To。
     */
    public String getTo() {
        return replace;
    }

    /**
     * 兼容旧字段名：To。
     */
    public void setTo(String to) {
        this.replace = to;
    }
}
