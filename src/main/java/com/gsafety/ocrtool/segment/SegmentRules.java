package com.gsafety.ocrtool.segment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次文档解析使用的不可变规则快照。
 *
 * <p>构造时深拷贝所有集合，防止解析过程中发布或缓存刷新改变当前结果。</p>
 */
public record SegmentRules(
        /** 指挥体系节点的稳定业务 key。 */
        String commandKey,
        List<String> commandAliases,
        Map<String, List<String>> responseAliases,
        Map<String, String> responseKeys,
        Map<String, List<String>> warningAliases,
        Map<String, String> warningKeys,
        /** 用于定位预警、响应和行动组候选范围的章节别名。 */
        Map<String, List<String>> sectionAliases,
        /** 用于识别条件、措施、继承和行动组入口的标记别名。 */
        Map<String, List<String>> markerAliases,
        List<String> responseTailHeadings,
        /** 由有序规则内容计算的版本摘要。 */
        String version) {

    /**
     * 规范化并深拷贝规则集合，保证快照不可变。
     */
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
        version = version == null ? "unversioned" : version;
    }

    public SegmentRules(
            String commandKey,
            List<String> commandAliases,
            Map<String, List<String>> responseAliases,
            Map<String, String> responseKeys,
            Map<String, List<String>> warningAliases,
            Map<String, String> warningKeys,
            Map<String, List<String>> sectionAliases,
            Map<String, List<String>> markerAliases,
            List<String> responseTailHeadings) {
        this(
                commandKey,
                commandAliases,
                responseAliases,
                responseKeys,
                warningAliases,
                warningKeys,
                sectionAliases,
                markerAliases,
                responseTailHeadings,
                "static");
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
                responseTailHeadings,
                "static");
    }

    private static Map<String, List<String>> immutableMapOfLists(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
