package com.gsafety.ocrtool.management;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 计算结构覆盖率的无状态工具。
 *
 * <p>它只验证人工确认的章节、响应等级和行动组是否被当前规则再次识别到；
 * 正文语义是否正确仍需由人工复核确认，因此结果明确命名为“结构覆盖率”。</p>
 */
final class PlanAccuracyEvaluator {

    private PlanAccuracyEvaluator() {
    }

    static Map<String, Object> evaluate(Map<String, Object> expected, Map<String, Object> actual) {
        Set<String> expectedItems = itemKeys(expected);
        Set<String> actualItems = itemKeys(actual);
        Set<String> matched = new LinkedHashSet<>(expectedItems);
        matched.retainAll(actualItems);
        Set<String> missing = new LinkedHashSet<>(expectedItems);
        missing.removeAll(actualItems);
        Set<String> unexpected = new LinkedHashSet<>(actualItems);
        unexpected.removeAll(expectedItems);

        int required = expectedItems.size();
        int covered = matched.size();
        BigDecimal percent = required == 0
                ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(covered * 100.0d / required).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("metric", "STRUCTURE_COVERAGE");
        summary.put("metricName", "结构覆盖率");
        summary.put("requiredItems", required);
        summary.put("matchedItems", covered);
        summary.put("coveragePercent", percent);
        summary.put("matched", List.copyOf(matched));
        summary.put("missing", List.copyOf(missing));
        summary.put("unexpected", List.copyOf(unexpected));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> itemKeys(Map<String, Object> result) {
        Set<String> keys = new LinkedHashSet<>();
        if (result == null) {
            return keys;
        }
        if (nonEmptyMap(result.get("commandSystem"))) {
            keys.add("commandSystem");
        }
        addLevelKeys(keys, "warning", result.get("warningResponses"));
        // 旧结果仅有 responseLevels 时，按照应急响应兼容处理。
        Object emergency = result.get("emergencyResponses");
        addLevelKeys(keys, "emergency", emergency instanceof Collection<?> ? emergency : result.get("responseLevels"));
        addActionGroupKeys(keys, result.get("actionGroups"));
        return keys;
    }

    private static boolean nonEmptyMap(Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty();
    }

    private static void addLevelKeys(Set<String> keys, String prefix, Object value) {
        if (!(value instanceof Collection<?> levels)) {
            return;
        }
        int index = 0;
        for (Object level : levels) {
            if (level instanceof Map<?, ?> map) {
                String key = text(map.get("key"));
                String title = text(map.get("title"));
                if (StringUtils.hasText(key) || StringUtils.hasText(title)) {
                    keys.add(prefix + ":" + (StringUtils.hasText(key) ? key : title));
                }
            }
            index++;
        }
    }

    private static void addActionGroupKeys(Set<String> keys, Object value) {
        if (!(value instanceof Collection<?> groups)) {
            return;
        }
        for (Object group : groups) {
            if (group instanceof Map<?, ?> map) {
                String key = text(map.get("key"));
                String name = text(map.get("name"));
                if (StringUtils.hasText(key) || StringUtils.hasText(name)) {
                    keys.add("actionGroup:" + (StringUtils.hasText(key) ? key : name));
                }
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
