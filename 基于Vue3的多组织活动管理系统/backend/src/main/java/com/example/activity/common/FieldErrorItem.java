package com.example.activity.common;

/**
 * 校验错误明细：字段路径与输入对象一致，不返回敏感字段原值（开发规范 2.3）。
 */
public record FieldErrorItem(String field, String message) {
}
