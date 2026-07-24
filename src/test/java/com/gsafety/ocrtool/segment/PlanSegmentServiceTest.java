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

    @Test
    void extractsSharedConditionsAndMeasuresFromUnstyledActivationHeadings() {
        ParsedDocument document = new ParsedDocument(
                "bus-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("5 应急响应", 1, 1),
                        block("5.1 分级响应", 1, 2),
                        block("发生特别重大事件时启动一级应急响应；发生重大事件时启动一级或二级应急响应；"
                                + "发生较大事件时启动三级或四级应急响应。", 1, 0),
                        block("市指挥部协调措施", 1, 0),
                        block("启动一级应急响应：", 1, 0),
                        block("组织一级救援力量赶赴现场。", 1, 0),
                        block("启动二级应急响应：", 1, 0),
                        block("组织二级救援力量赶赴现场。", 1, 0),
                        block("启动三级应急响应：", 1, 0),
                        block("组织三级救援力量赶赴现场。", 1, 0),
                        block("启动四级应急响应：", 1, 0),
                        block("组织四级救援力量赶赴现场。", 1, 0),
                        block("响应结束", 1, 2)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses()).allSatisfy(level -> {
            assertThat(level.status()).isEqualTo("EXTRACTED");
            assertThat(level.activationConditions()).contains("发生");
            assertThat(level.directResponseMeasures()).contains("救援力量赶赴现场");
        });
    }

    @Test
    void extractsNestedCompactNumberedResponseSections() {
        ParsedDocument document = new ParsedDocument(
                "natural-disaster-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("五、省级应急响应", 1, 1),
                        block("5.1一级响应", 1, 2),
                        block("5.1.1启动条件", 1, 3),
                        block("发生特别重大灾害时，可启动一级响应。", 1, 0),
                        block("5.1.3响应措施", 1, 3),
                        block("组织一级救灾力量开展救助。", 1, 0),
                        block("5.2二级响应", 1, 2),
                        block("5.2.1启动条件", 1, 3),
                        block("发生重大灾害时，可启动二级响应。", 1, 0),
                        block("5.2.3响应措施", 1, 3),
                        block("组织二级救灾力量开展救助。", 1, 0),
                        block("5.3三级响应", 1, 2),
                        block("5.3.1启动条件", 1, 3),
                        block("发生较大灾害时，可启动三级响应。", 1, 0),
                        block("5.3.3响应措施", 1, 3),
                        block("组织三级救灾力量开展救助。", 1, 0),
                        block("5.4四级响应", 1, 2),
                        block("5.4.1启动条件", 1, 3),
                        block("发生一般灾害时，可启动四级响应。", 1, 0),
                        block("5.4.3响应措施", 1, 3),
                        block("组织四级救灾力量开展救助。", 1, 0),
                        block("5.5启动条件调整", 1, 2)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses()).allSatisfy(level -> {
            assertThat(level.status()).isEqualTo("EXTRACTED");
            assertThat(level.activationConditions()).contains("灾害");
            assertThat(level.directResponseMeasures()).contains("救灾力量");
        });
    }

    @Test
    void splitsInlineActivationConditionAndMeasure() {
        ParsedDocument document = new ParsedDocument(
                "grain-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("5 分级响应", 1, 1),
                        block("5.1 一级应急响应", 1, 2),
                        block("出现特别重大粮食应急状态时，由指挥部启动一级应急响应，"
                                + "指挥部立即采取措施增加市场供给。", 1, 0),
                        block("5.2 二级应急响应", 1, 2),
                        block("出现重大粮食应急状态时，由指挥部启动二级应急响应，"
                                + "指挥部立即组织成员单位开展处置。", 1, 0),
                        block("响应终止", 1, 2)),
                List.of());

        SegmentResult result = service.extract(document);
        ResponseLevelSegment level1 = result.emergencyResponses().get(0);
        ResponseLevelSegment level2 = result.emergencyResponses().get(1);

        assertThat(level1.activationConditions()).contains("特别重大").doesNotContain("增加市场供给");
        assertThat(level1.directResponseMeasures()).contains("增加市场供给");
        assertThat(level2.activationConditions()).contains("重大粮食").doesNotContain("组织成员单位");
        assertThat(level2.directResponseMeasures()).contains("组织成员单位");
    }

    @Test
    void extractsRomanNumeralEmergencyResponseHeadingsFromConfiguredAliases() {
        ParsedDocument document = new ParsedDocument(
                "roman-level-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("应急响应", 1, 1),
                        block("Ⅰ级应急响应", 1, 2),
                        block("启动条件", 1, 3),
                        block("发生一级事件。", 1, 0),
                        block("响应措施", 1, 3),
                        block("组织救援力量开展一级处置。", 1, 0),
                        block("Ⅱ级应急响应", 1, 2),
                        block("启动条件", 1, 3),
                        block("发生二级事件。", 1, 0),
                        block("响应措施", 1, 3),
                        block("组织救援力量开展二级处置。", 1, 0),
                        block("Ⅲ级应急响应", 1, 2),
                        block("启动条件", 1, 3),
                        block("发生三级事件。", 1, 0),
                        block("响应措施", 1, 3),
                        block("组织救援力量开展三级处置。", 1, 0),
                        block("Ⅳ级应急响应", 1, 2),
                        block("启动条件", 1, 3),
                        block("发生四级事件。", 1, 0),
                        block("响应措施", 1, 3),
                        block("组织救援力量开展四级处置。", 1, 0),
                        block("响应终止", 1, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses()).extracting(ResponseLevelSegment::title)
                .containsExactly("Ⅰ级应急响应", "Ⅱ级应急响应", "Ⅲ级应急响应", "Ⅳ级应急响应");
        assertThat(result.emergencyResponses()).allSatisfy(level -> {
            assertThat(level.status()).isEqualTo("EXTRACTED");
            assertThat(level.activationConditions()).contains("事件");
            assertThat(level.directResponseMeasures()).contains("救援力量");
        });
    }

    @Test
    void recognizesDomainWordsReverseLevelsAndLegacyReactionTerms() {
        ParsedDocument document = new ParsedDocument(
                "legacy-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("4 应急响应", 1, 1),
                        block("4.1 Ⅰ级停电事件响应", 1, 2),
                        block("启动条件", 1, 3),
                        block("出现特别重大停电事件。", 1, 0),
                        block("响应行动", 1, 3),
                        block("组织一级抢修。", 1, 0),
                        block("4.2 应急响应（Ⅱ级）", 1, 2),
                        block("启动条件", 1, 3),
                        block("出现重大停电事件。", 1, 0),
                        block("响应措施", 1, 3),
                        block("组织二级抢修。", 1, 0),
                        block("4.3 Ⅲ级应急反应", 1, 2),
                        block("启动条件", 1, 3),
                        block("出现较大停电事件。", 1, 0),
                        block("反应行动", 1, 3),
                        block("组织三级抢修。", 1, 0),
                        block("响应终止", 1, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses().subList(0, 3))
                .allSatisfy(level -> assertThat(level.status()).isEqualTo("EXTRACTED"));
        assertThat(result.emergencyResponses().get(0).title()).contains("停电事件响应");
        assertThat(result.emergencyResponses().get(1).title()).contains("Ⅱ级");
        assertThat(result.emergencyResponses().get(2).title()).contains("应急反应");
        assertThat(result.emergencyResponses().get(3).status()).isEqualTo("MISSING");
    }

    @Test
    void mapsEventClassificationToConditionsAndKeepsResponseActionsAsMeasures() {
        ParsedDocument document = new ParsedDocument(
                "classification-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("1.3 事故分级", 1, 2),
                        block("1.3.1 特别重大事故", 1, 3),
                        block("造成三十人以上死亡或者一百人以上重伤。", 1, 0),
                        block("1.3.2 重大事故", 1, 3),
                        block("造成十人以上死亡或者五十人以上重伤。", 1, 0),
                        block("4 应急响应", 1, 1),
                        block("4.1 Ⅰ级响应行动", 1, 2),
                        block("国务院有关部门组织开展现场救援。", 1, 0),
                        block("4.2 Ⅱ级响应行动", 1, 2),
                        block("省级有关部门组织开展现场救援。", 1, 0),
                        block("响应终止", 1, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses().get(0).activationConditions()).contains("三十人以上死亡");
        assertThat(result.emergencyResponses().get(0).directResponseMeasures()).contains("国务院有关部门");
        assertThat(result.emergencyResponses().get(1).activationConditions()).contains("十人以上死亡");
        assertThat(result.emergencyResponses().get(1).directResponseMeasures()).contains("省级有关部门");
        assertThat(result.emergencyResponses().get(2).status()).isEqualTo("MISSING");
        assertThat(result.emergencyResponses().get(3).status()).isEqualTo("MISSING");
    }

    @Test
    void classificationDoesNotInventLevelsAbsentFromResponseStructure() {
        ParsedDocument document = new ParsedDocument(
                "three-level-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("1.3 灾害分级", 1, 2),
                        block("1.3.1 特别重大灾害", 1, 3),
                        block("造成三十人以上死亡。", 1, 0),
                        block("1.3.2 重大灾害", 1, 3),
                        block("造成十人以上死亡。", 1, 0),
                        block("1.3.3 较大灾害", 1, 3),
                        block("造成三人以上死亡。", 1, 0),
                        block("1.3.4 一般灾害", 1, 3),
                        block("造成三人以下死亡。", 1, 0),
                        block("4 应急响应", 2, 1),
                        block("4.1 Ⅰ级响应行动", 2, 2),
                        block("组织一级救援力量开展处置。", 2, 0),
                        block("4.2 Ⅱ级响应行动", 2, 2),
                        block("组织二级救援力量开展处置。", 2, 0),
                        block("4.3 Ⅲ级响应行动", 2, 2),
                        block("组织三级救援力量开展处置。", 2, 0),
                        block("4.4 工作组应急响应行动", 2, 2),
                        block("各工作组按照职责开展共同处置。", 2, 0),
                        block("响应终止", 3, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses().subList(0, 3))
                .allSatisfy(level -> assertThat(level.status()).isEqualTo("EXTRACTED"));
        ResponseLevelSegment level4 = result.emergencyResponses().get(3);
        assertThat(level4.status()).isEqualTo("MISSING");
        assertThat(level4.activationConditions()).isNull();
        assertThat(level4.directResponseMeasures()).isNull();
    }

    @Test
    void usesSemanticEvidenceOnlyForFieldsMissingFromStructuredSections() {
        ParsedDocument document = new ParsedDocument(
                "tiered-extraction-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("1.3 事故分级", 1, 2),
                        block("1.3.1 特别重大事故", 1, 3),
                        block("造成三十人以上死亡或者一百人以上重伤。", 1, 0),
                        block("5 应急响应", 2, 1),
                        block("5.1 Ⅰ级响应", 2, 2),
                        block("5.1.1 启动条件", 2, 3),
                        block("监测指标连续超过一级启动阈值。", 2, 0),
                        block("5.2 Ⅰ级响应", 3, 2),
                        block("市指挥部组织国家级救援力量赶赴现场。", 3, 0),
                        block("响应终止", 4, 1)),
                List.of());

        SegmentResult result = service.extract(document);
        ResponseLevelSegment level1 = result.emergencyResponses().get(0);

        assertThat(level1.activationConditions())
                .contains("连续超过一级启动阈值")
                .doesNotContain("三十人以上死亡");
        assertThat(level1.directResponseMeasures()).contains("国家级救援力量赶赴现场");
        assertThat(level1.status()).isEqualTo("EXTRACTED");
    }

    @Test
    void prefersExplicitForestFireConditionsOverGeneralClassification() {
        ParsedDocument document = new ParsedDocument(
                "forest-fire-plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("1.3 森林火灾分级", 1, 2),
                        block("森林火灾分为特别重大、重大、较大和一般四个等级。", 1, 0),
                        block("1.3.1 特别重大森林火灾", 1, 3),
                        block("受害森林面积在1000公顷以上。", 1, 0),
                        block("1.3.2 重大森林火灾", 1, 3),
                        block("受害森林面积在100公顷以上。", 1, 0),
                        block("4 主要任务", 2, 1),
                        block("4.1 组织灭火行动", 2, 2),
                        block("科学组织扑救，严防次生灾害。", 2, 0),
                        block("5 应急响应", 3, 1),
                        block("5.1 Ⅳ级响应", 3, 2),
                        block("5.1.1 启动条件", 3, 3),
                        block("初判发生一般森林火灾。", 3, 0),
                        block("符合上述条件之一时，按照以下程序启动响应：", 3, 0),
                        block("市森防办提出启动Ⅳ级响应建议。", 3, 0),
                        block("5.1.2 响应措施", 3, 3),
                        block("组织属地扑救力量开展处置。", 3, 0),
                        block("5.2 Ⅲ级响应", 4, 2),
                        block("5.2.1 启动条件", 4, 3),
                        block("初判发生较大森林火灾。", 4, 0),
                        block("5.2.2 响应措施", 4, 3),
                        block("组织市级扑救力量开展处置。", 4, 0),
                        block("5.3 Ⅱ级响应", 5, 2),
                        block("5.3.1 启动条件", 5, 3),
                        block("初判发生重大森林火灾。", 5, 0),
                        block("5.3.2 响应措施", 5, 3),
                        block("组织省级扑救力量开展处置。", 5, 0),
                        block("5.4 Ⅰ级响应", 6, 2),
                        block("5.4.1 启动条件", 6, 3),
                        block("初判发生特别重大森林火灾。", 6, 0),
                        block("在Ⅱ级应急响应基础上，经市指挥部办公室分析评估，认定灾情达到启动标准，"
                                + "向市指挥部提出启动Ⅰ级应急响应建议；", 6, 0),
                        block("5.4.2 响应措施", 6, 3),
                        block("组织国家级扑救力量开展处置。", 6, 0),
                        block("响应终止", 7, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses()).allSatisfy(level ->
                assertThat(level.activationConditions())
                        .doesNotContain("受害森林面积", "主要任务", "组织灭火行动", "按照以下程序"));
        assertThat(result.emergencyResponses().get(0).activationConditions()).contains("特别重大森林火灾");
        assertThat(result.emergencyResponses().get(0).activationConditions()).contains("启动Ⅰ级应急响应建议");
        assertThat(result.emergencyResponses().get(1).activationConditions())
                .contains("重大森林火灾")
                .doesNotContain("启动Ⅰ级应急响应建议");
        assertThat(result.emergencyResponses().get(2).activationConditions()).contains("较大森林火灾");
        assertThat(result.emergencyResponses().get(3).activationConditions()).contains("一般森林火灾");
        assertThat(result.emergencyResponses().get(3).directResponseMeasures())
                .contains("按照以下程序启动响应", "组织属地扑救力量");
    }

    @Test
    void ignoresCommandSystemDiagramsInAttachments() {
        ParsedDocument document = new ParsedDocument(
                "diagram-attachment.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("2 指挥体系", 2, 1),
                        block("市森林防灭火指挥部负责统一组织协调。", 2, 0),
                        block("3 应急响应", 3, 1),
                        block("附件：天津市森林防灭火指挥部组织体系框架图", 20, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.commandSystem().title()).isEqualTo("2 指挥体系");
        assertThat(result.commandSystem().content()).contains("统一组织协调").doesNotContain("框架图");
    }

    @Test
    void keepsWarningColorsOutOfEmergencyResponses() {
        ParsedDocument document = new ParsedDocument(
                "warning-only.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("3 监测预警", 1, 1),
                        block("3.1 红色预警", 1, 2),
                        block("预计发生特别严重灾害。", 1, 0),
                        block("3.2 橙色预警", 1, 2),
                        block("预计发生严重灾害。", 1, 0),
                        block("附则", 1, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.warningResponses().get(0).status()).isNotEqualTo("MISSING");
        assertThat(result.emergencyResponses()).allSatisfy(level -> assertThat(level.status()).isEqualTo("MISSING"));
    }

    @Test
    void distributesCommonResponseProcedureOnlyToDetectedLevels() {
        ParsedDocument document = new ParsedDocument(
                "common-procedure.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("4 应急响应", 1, 1),
                        block("4.1 响应程序", 1, 2),
                        block("指挥部统一组织信息报告并协调救援力量。", 1, 0),
                        block("4.2.1 Ⅰ级响应", 1, 3),
                        block("出现特别重大事故时启动Ⅰ级响应。", 1, 0),
                        block("组织国家级救援力量。", 1, 0),
                        block("4.2.2 Ⅱ级响应", 1, 3),
                        block("出现重大事故时启动Ⅱ级响应。", 1, 0),
                        block("组织省级救援力量。", 1, 0),
                        block("响应终止", 1, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses().get(0).directResponseMeasures()).contains("统一组织信息报告");
        assertThat(result.emergencyResponses().get(1).directResponseMeasures()).contains("统一组织信息报告");
        assertThat(result.emergencyResponses().get(2).status()).isEqualTo("MISSING");
        assertThat(result.emergencyResponses().get(3).status()).isEqualTo("MISSING");
    }

    @Test
    void splitsCombinedActionGroupsAndRejectsSummaryCounts() {
        ParsedDocument document = new ParsedDocument(
                "groups.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("2 指挥体系", 1, 1),
                        block("现场指挥部共设7个工作组。", 1, 0),
                        block("2.1 抢险救援组和医疗救护组", 1, 2),
                        block("负责人员搜救和伤员救治。", 1, 0)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.actionGroups()).extracting(ActionGroupSegment::name)
                .contains("抢险救援组", "医疗救护组")
                .noneMatch(name -> name.contains("7个"));
    }

    @Test
    void returnsStableWarningForNonPlanDocuments() {
        ParsedDocument document = new ParsedDocument(
                "law.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                List.of(
                        block("第一条 为了维护道路交通秩序，制定本法。", 1, 1),
                        block("第二条 中华人民共和国境内的车辆驾驶人应当遵守本法。", 1, 1)),
                List.of());

        SegmentResult result = service.extract(document);

        assertThat(result.emergencyResponses()).allSatisfy(level -> assertThat(level.status()).isEqualTo("MISSING"));
        assertThat(result.warnings()).contains("未识别到应急预案响应结构。");
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
