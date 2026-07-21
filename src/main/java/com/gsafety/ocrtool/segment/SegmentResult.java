package com.gsafety.ocrtool.segment;

import java.util.List;

public record SegmentResult(
        SegmentSection commandSystem,
        List<SegmentSection> responseLevels,
        List<ResponseLevelSegment> warningResponses,
        List<ResponseLevelSegment> emergencyResponses,
        List<ActionGroupSegment> actionGroups,
        List<String> warnings) {

    public SegmentResult(
            SegmentSection commandSystem,
            List<SegmentSection> responseLevels,
            List<String> warnings) {
        this(commandSystem, responseLevels, List.of(), List.of(), List.of(), warnings);
    }
}
