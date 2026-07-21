package com.gsafety.ocrtool.segment;

import java.util.List;

public record ResponseLevelSegment(
        String key,
        String category,
        String level,
        String title,
        String colorKey,
        String colorName,
        String status,
        String activationConditions,
        String directResponseMeasures,
        String responseMeasures,
        List<String> inheritedFromKeys,
        List<Integer> conditionSourcePages,
        List<Integer> measureSourcePages,
        MatchedBy matchedBy,
        List<String> matchEvidence) {
}
