package com.gsafety.ocrtool.segment;

import java.util.List;

public record SegmentSection(
        String key,
        String level,
        String title,
        String content,
        List<Integer> sourcePages,
        MatchedBy matchedBy,
        List<String> matchEvidence) {
}
