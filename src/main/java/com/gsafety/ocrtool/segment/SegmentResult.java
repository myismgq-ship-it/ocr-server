package com.gsafety.ocrtool.segment;

import java.util.List;

/**
 * 预案分段阶段的完整输出，保留告警和产生结果时使用的规则版本。
 */
public record SegmentResult(
        SegmentSection commandSystem,
        List<SegmentSection> responseLevels,
        List<ResponseLevelSegment> warningResponses,
        List<ResponseLevelSegment> emergencyResponses,
        List<ActionGroupSegment> actionGroups,
        List<String> warnings,
        /** 生成本结果的规则快照版本，用于审计和复现。 */
        String ruleVersion) {

    public SegmentResult(
            SegmentSection commandSystem,
            List<SegmentSection> responseLevels,
            List<String> warnings) {
        this(commandSystem, responseLevels, List.of(), List.of(), List.of(), warnings, "static");
    }

    public SegmentResult(
            SegmentSection commandSystem,
            List<SegmentSection> responseLevels,
            List<ResponseLevelSegment> warningResponses,
            List<ResponseLevelSegment> emergencyResponses,
            List<ActionGroupSegment> actionGroups,
            List<String> warnings) {
        this(
                commandSystem, responseLevels, warningResponses, emergencyResponses,
                actionGroups, warnings, "static");
    }
}
