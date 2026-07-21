package com.gsafety.ocrtool.response;

import java.util.List;

public record ActionGroupResponse(
        String key,
        String name,
        List<String> leadOrganizations,
        List<String> memberOrganizations,
        String responsibilities,
        String rawContent,
        List<Integer> sourcePages,
        String matchedBy,
        List<String> matchEvidence) {
}
