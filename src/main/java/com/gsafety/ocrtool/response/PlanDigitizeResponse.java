package com.gsafety.ocrtool.response;

import java.util.List;
/**
 * 预案数字化结构化结果。
 *
 * <p>包含兼容的 responseLevels，以及固定四级的预警/应急响应、行动组和规则版本。</p>
 */

public record PlanDigitizeResponse(
        String fileName,
        String fileType,
        String parseMode,
        /** WORD、PDF_TEXT、OCR 或 HYBRID。 */
        PlanSectionResponse commandSystem,
        List<ResponseLevelSectionResponse> responseLevels,
        List<ResponseLevelSectionResponse> warningResponses,
        /** 固定返回四级预警结果。 */
        List<ResponseLevelSectionResponse> emergencyResponses,
        /** 固定返回四级应急响应结果。 */
        List<ActionGroupResponse> actionGroups,
        /** 从章节标题、表格或行内列表提取的行动组。 */
        List<String> warnings,
        String ruleVersion) {
        /** 产生本结果的规则快照版本。 */

    public PlanDigitizeResponse(
            String fileName,
            String fileType,
            String parseMode,
            PlanSectionResponse commandSystem,
            List<ResponseLevelSectionResponse> responseLevels,
            List<ResponseLevelSectionResponse> warningResponses,
            List<ResponseLevelSectionResponse> emergencyResponses,
            List<ActionGroupResponse> actionGroups,
            List<String> warnings) {
        this(
                fileName,
                fileType,
                parseMode,
                commandSystem,
                responseLevels,
                warningResponses,
                emergencyResponses,
                actionGroups,
                warnings,
                null);
    }

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
                warnings,
                null);
    }
}
