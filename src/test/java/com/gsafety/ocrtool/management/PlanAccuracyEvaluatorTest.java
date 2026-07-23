package com.gsafety.ocrtool.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanAccuracyEvaluatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void evaluatesExpectedSectionsAndKeepsLegacyResponseLevelsCompatible() {
        Map<String, Object> expected = Map.of(
                "commandSystem", Map.of("key", "command"),
                "emergencyResponses", List.of(Map.of("key", "level_1"), Map.of("key", "level_2")),
                "warningResponses", List.of(Map.of("key", "warning_1")),
                "actionGroups", List.of(Map.of("key", "rescue")));
        Map<String, Object> actual = Map.of(
                "commandSystem", Map.of("key", "command"),
                "responseLevels", List.of(Map.of("key", "level_1")),
                "warningResponses", List.of(Map.of("key", "warning_1")),
                "actionGroups", List.of(Map.of("key", "rescue"), Map.of("key", "extra")));

        Map<String, Object> summary = PlanAccuracyEvaluator.evaluate(expected, actual);

        assertEquals("STRUCTURE_COVERAGE", summary.get("metric"));
        assertEquals(5, summary.get("requiredItems"));
        assertEquals(4, summary.get("matchedItems"));
        assertEquals(0, ((BigDecimal) summary.get("coveragePercent")).compareTo(new BigDecimal("80.00")));
        assertTrue(((List<Object>) summary.get("missing")).contains("emergency:level_2"));
        assertTrue(((List<Object>) summary.get("unexpected")).contains("actionGroup:extra"));
    }
}
