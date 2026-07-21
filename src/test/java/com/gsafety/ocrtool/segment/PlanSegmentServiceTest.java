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

    private DocumentBlock block(String text, int page, int headingLevel) {
        return new DocumentBlock(text, page, headingLevel, false, List.of());
    }

    private DocumentBlock table(String... cells) {
        return new DocumentBlock(String.join(" ", cells), 1, 0, true, List.of(cells));
    }

    private SegmentSection find(SegmentResult result, String level) {
        return result.responseLevels().stream()
                .filter(section -> level.equals(section.level()))
                .findFirst()
                .orElseThrow();
    }
}
