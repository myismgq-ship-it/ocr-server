package com.gsafety.ocrtool.management;

/**
 * 一个可版本化的预案规则定义。
 *
 * @param ruleType 规则类型
 * @param ruleCode 稳定业务编码
 * @param canonicalName 标准显示名称
 * @param alias 文档匹配别名
 * @param groupOrder 规则组排序
 * @param aliasOrder 组内别名排序
 * @param enabled 是否发布到活动规则表
 */

public record PlanRuleDefinition(
        String ruleType,
        String ruleCode,
        String canonicalName,
        String alias,
        int groupOrder,
        int aliasOrder,
        boolean enabled) {
}
