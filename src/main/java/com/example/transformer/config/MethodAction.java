package com.example.transformer.config;

/**
 * 方法规则的动作类型：
 * - ADD: 新增方法
 * - DELETE: 删除方法
 * - REPLACE: 删除旧方法并新增新方法
 * - MODIFY: 在原方法上做增量修改（签名/注解/参数/方法体）
 */
public enum MethodAction {
    ADD,
    DELETE,
    REPLACE,
    MODIFY
}

