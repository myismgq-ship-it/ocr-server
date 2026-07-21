package com.gsafety.ocrtool.response;

import java.util.List;

public record PlanSectionResponse(
        String key,
        String title,
        String content,
        List<Integer> sourcePages,
        String matchedBy,
        List<String> matchEvidence) {
}
