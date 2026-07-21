package com.gsafety.ocrtool.plan;

import com.gsafety.ocrtool.document.DocumentBlock;
import com.gsafety.ocrtool.document.DocumentDownloadService;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.document.DocumentParseMode;
import com.gsafety.ocrtool.document.DocumentParseService;
import com.gsafety.ocrtool.document.DocumentUploadService;
import com.gsafety.ocrtool.document.DownloadedDocument;
import com.gsafety.ocrtool.document.ParsedDocument;
import com.gsafety.ocrtool.segment.PlanSegmentService;
import com.gsafety.ocrtool.segment.TestSegmentRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanDigitizeServiceTest {

    @Test
    void extractsExplicitResponseMeasuresField() throws Exception {
        PlanDigitizeService service = serviceWithBlocks(List.of(
                block("Ⅳ级响应"),
                block("启动条件"),
                block("发生一般事件。"),
                block("响应措施"),
                block("区指挥部进入应急状态。"),
                block("视情发布响应信息。")
        ));

        var response = service.digitize("https://example.com/plan.docx");

        assertThat(response.responseLevels().get(0).content())
                .isEqualTo("启动条件\n发生一般事件。");
        assertThat(response.responseLevels().get(0).responseMeasures())
                .isEqualTo("区指挥部进入应急状态。\n视情发布响应信息。");
    }

    @Test
    void extractsEmergencyMeasuresSentenceAsResponseMeasures() throws Exception {
        PlanDigitizeService service = serviceWithBlocks(List.of(
                block("Ⅱ级响应"),
                block("启动条件"),
                block("发生重大事件。"),
                block("在Ⅲ级响应的基础上，加强以下应急措施："),
                block("市指挥部及时报告火场情况。"),
                block("协调做好扑火物资调拨。")
        ));

        var response = service.digitize("https://example.com/plan.docx");

        assertThat(response.responseLevels().get(0).content())
                .isEqualTo("启动条件\n发生重大事件。");
        assertThat(response.responseLevels().get(0).responseMeasures())
                .isEqualTo("在Ⅲ级响应的基础上，加强以下应急措施：\n市指挥部及时报告火场情况。\n协调做好扑火物资调拨。");
    }

    private PlanDigitizeService serviceWithBlocks(List<DocumentBlock> blocks) throws Exception {
        Path path = Files.createTempFile("plan-test-", ".docx");
        DocumentDownloadService downloadService = mock(DocumentDownloadService.class);
        DocumentUploadService uploadService = mock(DocumentUploadService.class);
        DocumentParseService parseService = mock(DocumentParseService.class);
        DownloadedDocument document = new DownloadedDocument(
                path,
                "plan.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                1,
                DocumentFileType.DOCX);
        when(downloadService.download(any())).thenReturn(document);
        when(parseService.parse(any())).thenReturn(new ParsedDocument(
                "plan.docx",
                DocumentFileType.DOCX,
                DocumentParseMode.WORD,
                blocks,
                List.of()));
        return new PlanDigitizeService(
                downloadService,
                uploadService,
                parseService,
                new PlanSegmentService(TestSegmentRules::defaults));
    }

    private DocumentBlock block(String text) {
        return new DocumentBlock(text, 1, 0, false, List.of());
    }
}
