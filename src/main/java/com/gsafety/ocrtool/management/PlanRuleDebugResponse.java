package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import java.util.List;
import java.util.UUID;

/** 管理端单次规则命中调试响应；不持久化到任务或规则版本表。 */
public record PlanRuleDebugResponse(
        UUID revisionId,
        int revisionNumber,
        String revisionStatus,
        String ruleSource,
        String ruleLabel,
        String ruleVersion,
        PlanDigitizeResponse result,
        RuleDebugSummaryResponse summary,
        List<RuleDebugTraceResponse> traces,
        List<String> warnings) {
}
