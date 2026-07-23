package com.gsafety.ocrtool.segment;

/**
 * 数据库规则行的最小只读表示。
 *
 * @param ruleType COMMAND/RESPONSE/WARNING/SECTION/MARKER/TAIL
 * @param ruleCode 稳定业务编码，同组规则共享
 * @param canonicalName 对外标准名称
 * @param alias 可在文档中命中的别名
 */
public record SegmentRuleRow(String ruleType, String ruleCode, String canonicalName, String alias) {
}
