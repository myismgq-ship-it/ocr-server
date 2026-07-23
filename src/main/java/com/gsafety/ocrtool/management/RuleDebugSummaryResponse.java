package com.gsafety.ocrtool.management;

/** 规则调试统计摘要。 */
public record RuleDebugSummaryResponse(
        int totalRules,
        int matchedRules,
        int fallbackRules,
        int missingRules,
        int parsedBlocks) {
}
