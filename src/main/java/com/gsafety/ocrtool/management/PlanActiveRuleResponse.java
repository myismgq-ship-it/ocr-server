package com.gsafety.ocrtool.management;

import java.util.List;

/**
 * Current runtime snapshot of enabled plan segment rules.
 *
 * <p>The response is read from {@code plan_segment_rule}, making the active database rules
 * distinguishable from editable revision history.</p>
 */
public record PlanActiveRuleResponse(
        String source,
        String label,
        String ruleVersion,
        List<PlanRuleDefinition> rules) {
}