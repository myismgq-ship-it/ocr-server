package com.gsafety.ocrtool.segment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TestSegmentRules {

    private TestSegmentRules() {
    }

    public static SegmentRules defaults() {
        Map<String, List<String>> responseAliases = new LinkedHashMap<>();
        responseAliases.put("一级响应", List.of("一级响应", "一级应急响应", "Ⅰ级响应", "Ⅰ级应急响应", "I级响应", "特别重大响应"));
        responseAliases.put("二级响应", List.of("二级响应", "二级应急响应", "Ⅱ级响应", "Ⅱ级应急响应", "II级响应", "重大响应"));
        responseAliases.put("三级响应", List.of("三级响应", "三级应急响应", "Ⅲ级响应", "Ⅲ级应急响应", "III级响应", "较大响应"));
        responseAliases.put("四级响应", List.of("四级响应", "四级应急响应", "Ⅳ级响应", "Ⅳ级应急响应", "IV级响应", "一般响应"));
        Map<String, String> responseKeys = new LinkedHashMap<>();
        responseKeys.put("一级响应", "level_1");
        responseKeys.put("二级响应", "level_2");
        responseKeys.put("三级响应", "level_3");
        responseKeys.put("四级响应", "level_4");
        return new SegmentRules(
                "command_system",
                List.of("指挥体系", "组织指挥体系", "应急指挥体系", "指挥机构及职责", "组织机构及职责"),
                responseAliases,
                responseKeys,
                List.of("启动条件调整", "响应终止", "综合保障", "后期处置", "附则", "附件"));
    }
}
