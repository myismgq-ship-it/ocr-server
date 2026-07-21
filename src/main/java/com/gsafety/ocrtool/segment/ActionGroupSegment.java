package com.gsafety.ocrtool.segment;

import java.util.List;

public record ActionGroupSegment(
        String key,
        String name,
        List<String> leadOrganizations,
        List<String> memberOrganizations,
        String responsibilities,
        String rawContent,
        List<Integer> sourcePages,
        MatchedBy matchedBy,
        List<String> matchEvidence) {
}
