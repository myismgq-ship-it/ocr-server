package com.gsafety.ocrtool.management;

import java.util.List;

/** 单个规则组的可视化命中链路。 */
public record RuleDebugTraceResponse(
        String ruleType,
        String ruleCode,
        String canonicalName,
        String status,
        String selectedAlias,
        String matchedBy,
        String reason,
        List<RuleDebugCandidateResponse> candidates) {
}
