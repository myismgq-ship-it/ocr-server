package com.gsafety.ocrtool.response;

import java.util.List;

public record PlanDigitizeResponse(
        String fileName,
        String fileType,
        String parseMode,
        PlanSectionResponse commandSystem,
        List<ResponseLevelSectionResponse> responseLevels,
        List<ResponseLevelSectionResponse> warningResponses,
        List<ResponseLevelSectionResponse> emergencyResponses,
        List<ActionGroupResponse> actionGroups,
        List<String> warnings) {

    public PlanDigitizeResponse(
            String fileName,
            String fileType,
            String parseMode,
            PlanSectionResponse commandSystem,
            List<ResponseLevelSectionResponse> responseLevels,
            List<String> warnings) {
        this(
                fileName,
                fileType,
                parseMode,
                commandSystem,
                responseLevels,
                List.of(),
                responseLevels,
                List.of(),
                warnings);
    }
}
