package com.gsafety.ocrtool.response;

import java.util.List;

public record ResponseLevelSectionResponse(
        String key,
        String level,
        String title,
        String content,
        String responseMeasures,
        List<Integer> sourcePages,
        String matchedBy,
        List<String> matchEvidence,
        String category,
        String status,
        String colorKey,
        String colorName,
        String activationConditions,
        String directResponseMeasures,
        List<String> inheritedFromKeys,
        List<Integer> conditionSourcePages,
        List<Integer> measureSourcePages) {

    public ResponseLevelSectionResponse(
            String key,
            String level,
            String title,
            String content,
            String responseMeasures,
            List<Integer> sourcePages,
            String matchedBy,
            List<String> matchEvidence) {
        this(
                key,
                level,
                title,
                content,
                responseMeasures,
                sourcePages,
                matchedBy,
                matchEvidence,
                "EMERGENCY",
                content == null && responseMeasures == null ? "MISSING" : "EXTRACTED",
                null,
                null,
                content,
                responseMeasures,
                List.of(),
                sourcePages,
                sourcePages);
    }
}
