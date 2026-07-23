package com.gsafety.ocrtool.segment;

import com.gsafety.ocrtool.document.DocumentBlock;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.document.DocumentParseMode;
import com.gsafety.ocrtool.document.ParsedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanSegmentServiceTest {

    private final PlanSegmentService service = new PlanSegmentService(TestSegmentRules::defaults);

    @Test
    void extractsCommandSystemAndResponseAliasesFromHeadings() {
        ParsedDocument document = new ParsedDocument("plan.docx", DocumentFileType.DOCX, DocumentParseMode.WORD, List.of(
                block("目录", 1, 1),
                block("Ⅰ级响应 8", 1, 0),
                block("二、组织指挥体系", 4, 1),
                block("成立应急指挥部，统一组织协调事故处置。", 4, 0),
                block("三、应急响应", 6, 1),
                block("Ⅰ级响应", 7, 2),
                block("发生特别重大事件时启动一级响应。", 7, 0),
                block("重大响应", 8, 2),
                block("发生重大事件时启动二级响应。", 8, 0),
                block("较大响应", 9, 2),
                block("发生较大事件时启动三级响应。", 9, 0)
        ), List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.commandSystem().title()).isEqualTo("二、组织指挥体系");
        assertThat(result.commandSystem().key()).isEqualTo("command_system");
        assertThat(result.commandSystem().matchedBy()).isEqualTo(MatchedBy.HEADING);
        assertThat(result.commandSystem().content()).contains("应急指挥部");
        assertThat(result.responseLevels()).extracting(SegmentSection::level)
                .contains("一级响应", "二级响应", "三级响应");
        assertThat(result.responseLevels()).extracting(SegmentSection::key)
                .contains("level_1", "level_2", "level_3");
        assertThat(result.responseLevels().get(0).matchedBy()).isEqualTo(MatchedBy.HEADING_ALIAS);
        assertThat(result.warnings()).contains("未识别到四级响应内容。");
    }

    @Test
    void extractsResponseLevelFromTableRow() {
        ParsedDocument document = new ParsedDocument("plan.docx", DocumentFileType.DOCX, DocumentParseMode.WORD, List.of(
                table("响应等级", "启动条件", "主要措施"),
                table("四级响应", "发生一般事件", "现场处置组立即处置")
        ), List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.responseLevels()).hasSize(1);
        SegmentSection section = result.responseLevels().get(0);
        assertThat(section.level()).isEqualTo("四级响应");
        assertThat(section.matchedBy()).isEqualTo(MatchedBy.TABLE_ROW);
        assertThat(section.content()).contains("现场处置组");
    }

    @Test
    void responseSectionsStopAtNextResponseOrTailHeading() {
        ParsedDocument document = new ParsedDocument("plan.docx", DocumentFileType.DOCX, DocumentParseMode.WORD, List.of(
                block("Ⅳ级响应", 1, 0),
                block("四级启动条件", 1, 0),
                block("Ⅲ级响应", 1, 0),
                block("三级启动条件", 1, 0),
                block("Ⅱ级响应", 1, 0),
                block("二级启动条件", 1, 0),
                block("Ⅰ级响应", 1, 0),
                block("一级启动条件", 1, 0),
                block("启动条件调整", 1, 0),
                block("后续章节内容", 1, 0)
        ), List.of());

        SegmentResult result = service.extract(document);

        SegmentSection level4 = find(result, "四级响应");
        SegmentSection level3 = find(result, "三级响应");
        SegmentSection level2 = find(result, "二级响应");
        SegmentSection level1 = find(result, "一级响应");
        assertThat(level4.content()).contains("四级启动条件").doesNotContain("三级启动条件");
        assertThat(level3.content()).contains("三级启动条件").doesNotContain("二级启动条件");
        assertThat(level2.content()).contains("二级启动条件").doesNotContain("一级启动条件");
        assertThat(level1.content()).contains("一级启动条件").doesNotContain("启动条件调整", "后续章节内容");
    }

    @Test
    void returnsFourWarningLevelsAndExpandsInheritedMeasures() {
        ParsedDocument document = new ParsedDocument("plan.docx", DocumentFileType.DOCX, DocumentParseMode.WORD, List.of(
                block("蓝色预警", 2, 2),
                block("发布条件", 2, 0),
                block("预计可能发生一般事件。", 2, 0),
                block("响应措施", 2, 0),
                block("通知有关单位做好准备。", 2, 0),
                block("黄色预警", 3, 2),
                block("发布条件", 3, 0),
                block("预计可能发生较大事件。", 3, 0),
                block("响应措施", 3, 0),
                block("在蓝色预警响应措施基础上，加强监测预报。", 3, 0)
        ), List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.warningResponses()).hasSize(4);
        ResponseLevelSegment yellow = result.warningResponses().get(2);
        assertThat(yellow.key()).isEqualTo("warning_level_3");
        assertThat(yellow.inheritedFromKeys()).contains("warning_level_4");
        assertThat(yellow.responseMeasures())
                .contains("通知有关单位做好准备。", "加强监测预报");
        assertThat(result.warningResponses().get(0).status()).isEqualTo("MISSING");
    }

    @Test
    void mergesActionGroupCompositionAndResponsibilitiesByStableKey() {
        ParsedDocument document = new ParsedDocument("plan.docx", DocumentFileType.DOCX, DocumentParseMode.WORD, List.of(
                block("2.3.1 综合协调组", 4, 3),
                block("由市应急局牵头，市公安局、市卫健委参加。", 4, 0),
                block("（1）综合协调组。主要负责信息汇总和综合协调。", 9, 0)
        ), List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.actionGroups()).hasSize(1);
        ActionGroupSegment group = result.actionGroups().get(0);
        assertThat(group.key()).startsWith("action_group_").hasSize(29);
        assertThat(group.name()).isEqualTo("综合协调组");
        assertThat(group.leadOrganizations()).contains("市应急局");
        assertThat(group.responsibilities()).contains("信息汇总和综合协调");
        assertThat(group.sourcePages()).containsExactly(4, 9);
    }

    @Test
    void appliesGroupedWarningMeasuresToEveryListedColor() {
        ParsedDocument document = new ParsedDocument("plan.docx", DocumentFileType.DOCX, DocumentParseMode.WORD, List.of(
                block("预警响应措施", 2, 1),
                block("黄色、蓝色预警响应措施包括通知有关单位做好准备。", 2, 0)
        ), List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.warningResponses().get(2).directResponseMeasures()).contains("通知有关单位");
        assertThat(result.warningResponses().get(3).directResponseMeasures()).contains("通知有关单位");
    }

    @Test
    void extractsActivationConditionsFromSidewaysResponseTableColumns() {
        ParsedDocument document = new ParsedDocument("plan.pdf", DocumentFileType.PDF, DocumentParseMode.OCR, List.of(
                table("四级响应"),
                table("符合以下情形之一时，"),
                table("启动四级响应："),
                table("1. 四级条件内容。"),
                table("三级响应"),
                table("符合以下情形之一时，"),
                table("启动三级响应："),
                table("1. 三级条件内容。"),
                table("二级响应"),
                table("符合以下情形之一时，"),
                table("启动二级响应："),
                table("1. 二级条件内容。"),
                table("一级响应"),
                table("符合以下情形之一时，"),
                table("启动一级响应："),
                table("1. 一级条件内容。"),
                table("4. 二级应急响应不能控"),
                table("制；")
        ), List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses()).extracting(ResponseLevelSegment::activationConditions)
                .containsExactly(
                        "符合以下情形之一时，\n启动一级响应：\n1. 一级条件内容。\n4. 二级应急响应不能控\n制；",
                        "符合以下情形之一时，\n启动二级响应：\n1. 二级条件内容。",
                        "符合以下情形之一时，\n启动三级响应：\n1. 三级条件内容。",
                        "符合以下情形之一时，\n启动四级响应：\n1. 四级条件内容。");
        assertThat(result.emergencyResponses()).extracting(ResponseLevelSegment::directResponseMeasures)
                .containsOnlyNulls();
    }

    @Test
    void separatesNumberedResponseMeasuresFromAppendixConditions() {
        ParsedDocument document = new ParsedDocument("fire-plan.pdf", DocumentFileType.PDF, DocumentParseMode.OCR, List.of(
                block("5.3 市级响应", 9, 2),
                block("火灾事故发生后，依据响应条件，启动相应等级响应。", 9, 0),
                block("5.3.1 四级响应", 9, 3),
                block("符合四级响应条件时，市指挥部办公室主任启动四级响应。", 9, 0),
                block("市指挥部办公室密切关注火场态势，做好应急出动准备。", 9, 0),
                block("5.3.2 三级响应", 9, 3),
                block("符合三级响应条件时，由指挥长宣布启动三级响应。重点做好以下工作：", 9, 0),
                block("(1)通知有关成员单位和救援力量立即赶赴现场。", 9, 0),
                block("5.3.3 一、二级响应", 10, 3),
                block("符合一、二级响应条件时，建议启动一级或二级响应。", 10, 0),
                block("在做好三级响应重点工作的基础上，落实上级工作组指导意见。", 10, 0),
                block("5.3.5 响应结束", 11, 3),
                block("一级、二级、三级响应由现场指挥长宣布响应结束，四级响应由办公室决定结束。", 11, 0),
                block("附件1 市级火灾事故应急响应流程图", 13, 1),
                block("四级响应", 13, 0),
                block("采取响应措施", 13, 0),
                tableAt(18, "四级响应"),
                tableAt(18, "符合以下情形之一时，"),
                tableAt(18, "启动四级响应："),
                tableAt(18, "1. 四级条件。"),
                tableAt(18, "三级响应"),
                tableAt(18, "符合以下情形之一时，"),
                tableAt(18, "启动三级响应："),
                tableAt(18, "1. 三级条件。"),
                tableAt(18, "二级响应"),
                tableAt(18, "符合以下情形之一时，"),
                tableAt(18, "启动二级响应："),
                tableAt(18, "1. 二级条件。"),
                tableAt(18, "一级响应"),
                tableAt(18, "符合以下情形之一时，"),
                tableAt(18, "启动一级响应："),
                tableAt(18, "1. 一级条件。"),
                block("抄送：市委办公室。", 19, 0)
        ), List.of());

        SegmentResult result = service.extract(document);

        ResponseLevelSegment level1 = result.emergencyResponses().get(0);
        ResponseLevelSegment level2 = result.emergencyResponses().get(1);
        ResponseLevelSegment level3 = result.emergencyResponses().get(2);
        ResponseLevelSegment level4 = result.emergencyResponses().get(3);
        assertThat(level1.title()).isEqualTo("一级响应");
        assertThat(level2.title()).isEqualTo("二级响应");
        assertThat(level1.matchEvidence()).contains("5.3.3 一、二级响应");
        assertThat(level2.matchEvidence()).contains("5.3.3 一、二级响应");
        assertThat(level1.activationConditions()).contains("一级条件").doesNotContain("抄送", "指导意见");
        assertThat(level2.activationConditions()).contains("二级条件").doesNotContain("指导意见");
        assertThat(level3.activationConditions()).contains("三级条件").doesNotContain("赶赴现场");
        assertThat(level4.activationConditions()).contains("四级条件").doesNotContain("密切关注");
        assertThat(level1.directResponseMeasures()).contains("建议启动一级或二级响应", "指导意见");
        assertThat(level2.directResponseMeasures()).contains("建议启动一级或二级响应", "指导意见");
        assertThat(level3.directResponseMeasures()).contains("赶赴现场");
        assertThat(level4.directResponseMeasures()).contains("密切关注");
        assertThat(result.emergencyResponses()).allSatisfy(level -> {
            assertThat(level.status()).isEqualTo("EXTRACTED");
            assertThat(level.activationConditions()).doesNotContain("流程图", "响应结束");
            assertThat(level.directResponseMeasures()).doesNotContain("流程图", "响应结束");
            assertThat(level.conditionSourcePages()).containsExactly(18);
        });
    }

    @Test
    void mergesConditionsMeasuresAndActionGroupsAcrossDifferentSections() {
        SegmentRules defaults = TestSegmentRules.defaults();
        SegmentRules scopedRules = new SegmentRules(
                defaults.commandKey(),
                defaults.commandAliases(),
                defaults.responseAliases(),
                defaults.responseKeys(),
                defaults.warningAliases(),
                defaults.warningKeys(),
                java.util.Map.of(
                        "emergency_scope", List.of("应急响应", "分级响应"),
                        "action_group_scope", List.of("工作组")),
                java.util.Map.of(
                        "activation_condition", List.of("启动条件", "响应条件"),
                        "response_measure", List.of("响应措施", "响应行动"),
                        "group_responsibility", List.of("主要负责")),
                defaults.responseTailHeadings(),
                "earthquake-regression");
        PlanSegmentService scopedService = new PlanSegmentService(() -> scopedRules);
        ParsedDocument document = new ParsedDocument(
                "天津市地震应急预案.doc",
                DocumentFileType.DOC,
                DocumentParseMode.WORD,
                List.of(
                        block("2.3 市抗震救灾指挥部工作组", 1, 2),
                        block("2.3.1 综合协调工作组", 1, 3),
                        block("负责指挥部日常协调工作。", 1, 0),
                        block("4.3 应急响应分级", 1, 2),
                        block("符合特别重大地震灾害判定条件时，建议启动一级应急响应。", 1, 0),
                        block("符合重大地震灾害判定条件时，建议启动二级应急响应。", 1, 0),
                        block("符合较大地震灾害判定条件时，建议启动三级应急响应。", 1, 0),
                        block("5 应急响应", 1, 1),
                        block("5.2 分级响应", 1, 2),
                        block("5.2.1 一级应急响应", 1, 3),
                        block("市抗震救灾指挥部组织开展抢险救援和群众安置。", 1, 0),
                        block("5.2.2 二级应急响应", 1, 3),
                        block("市有关部门立即派出工作组赶赴灾区。", 1, 0),
                        block("5.2.3 三级应急响应", 1, 3),
                        block("事发地区组织开展先期处置。", 1, 0),
                        block("5.3 工作组应急响应行动", 1, 2),
                        block("（1）综合协调组。主要负责信息汇总和综合协调。", 1, 0),
                        block("（2）抢救抢险组。组织人员搜救，协调开展工程抢险。", 1, 0)),
                List.of());

        SegmentResult result = scopedService.extract(document);

        assertThat(result.emergencyResponses().get(0).activationConditions()).contains("特别重大地震灾害");
        assertThat(result.emergencyResponses().get(0).directResponseMeasures()).contains("抢险救援和群众安置");
        assertThat(result.emergencyResponses().get(1).activationConditions()).contains("重大地震灾害");
        assertThat(result.emergencyResponses().get(1).directResponseMeasures()).contains("赶赴灾区");
        assertThat(result.emergencyResponses().get(2).activationConditions()).contains("较大地震灾害");
        assertThat(result.emergencyResponses().get(2).directResponseMeasures()).contains("先期处置");
        assertThat(result.actionGroups()).extracting(ActionGroupSegment::name)
                .contains("综合协调组", "抢救抢险组");
        assertThat(result.actionGroups().stream()
                .filter(group -> "综合协调组".equals(group.name()))
                .findFirst()
                .orElseThrow()
                .responsibilities()).contains("信息汇总和综合协调");
        assertThat(result.actionGroups().stream()
                .filter(group -> "抢救抢险组".equals(group.name()))
                .findFirst()
                .orElseThrow()
                .responsibilities()).contains("人员搜救", "工程抢险");
    }

    private DocumentBlock block(String text, int page, int headingLevel) {
        return new DocumentBlock(text, page, headingLevel, false, List.of());
    }

    private DocumentBlock table(String... cells) {
        return new DocumentBlock(String.join(" ", cells), 1, 0, true, List.of(cells));
    }

    private DocumentBlock tableAt(int page, String... cells) {
        return new DocumentBlock(String.join(" ", cells), page, 0, true, List.of(cells));
    }

    private SegmentSection find(SegmentResult result, String level) {
        return result.responseLevels().stream()
                .filter(section -> level.equals(section.level()))
                .findFirst()
                .orElseThrow();
    }
}
