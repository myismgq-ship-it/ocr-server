package com.gsafety.ocrtool.segment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SegmentRules(
        String commandKey,
        List<String> commandAliases,
        Map<String, List<String>> responseAliases,
        Map<String, String> responseKeys,
        Map<String, List<String>> warningAliases,
        Map<String, String> warningKeys,
        Map<String, List<String>> sectionAliases,
        Map<String, List<String>> markerAliases,
        List<String> responseTailHeadings) {

    public SegmentRules {
        commandAliases = List.copyOf(commandAliases);
        Map<String, List<String>> copiedResponseAliases = new LinkedHashMap<>();
        responseAliases.forEach((name, aliases) -> copiedResponseAliases.put(name, List.copyOf(aliases)));
        responseAliases = Collections.unmodifiableMap(copiedResponseAliases);
        responseKeys = Collections.unmodifiableMap(new LinkedHashMap<>(responseKeys));
        warningAliases = immutableMapOfLists(warningAliases);
        warningKeys = Collections.unmodifiableMap(new LinkedHashMap<>(warningKeys));
        sectionAliases = immutableMapOfLists(sectionAliases);
        markerAliases = immutableMapOfLists(markerAliases);
        responseTailHeadings = List.copyOf(responseTailHeadings);
    }

    public SegmentRules(
            String commandKey,
            List<String> commandAliases,
            Map<String, List<String>> responseAliases,
            Map<String, String> responseKeys,
            List<String> responseTailHeadings) {
        this(
                commandKey,
                commandAliases,
                responseAliases,
                responseKeys,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                responseTailHeadings);
    }

    private static Map<String, List<String>> immutableMapOfLists(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
