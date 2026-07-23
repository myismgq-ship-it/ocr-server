package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.engine.OcrEngine;
import com.gsafety.ocrtool.preprocess.ImagePreprocessor;
import com.gsafety.ocrtool.preprocess.PreprocessedImage;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrPoint;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfDocumentParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTextAndScannedPagesAsHybrid() throws Exception {
        Path pdfPath = tempDir.resolve("hybrid.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage textPage = new PDPage();
            pdf.addPage(textPage);
            try (PDPageContentStream content = new PDPageContentStream(pdf, textPage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Emergency command system and response plan");
                content.endText();
            }
            pdf.addPage(new PDPage());
            pdf.save(pdfPath.toFile());
        }

        PlanProperties properties = new PlanProperties();
        properties.getPdf().setTextMinChars(8);
        properties.getPdf().setOcrDpi(72);
        OcrEngine engine = mock(OcrEngine.class);
        when(engine.recognize(any(Path.class))).thenReturn(new OcrResult(
                "scanned response",
                List.of(new OcrLine(
                        "scanned response",
                        0.96,
                        List.of(new OcrPoint(1, 1), new OcrPoint(20, 1))))));

        PdfDocumentParser parser = new PdfDocumentParser(properties, engine, identityPreprocessor());
        ParsedDocument result = parser.parse(new DownloadedDocument(
                pdfPath,
                "hybrid.pdf",
                "application/pdf",
                Files.size(pdfPath),
                DocumentFileType.PDF));

        assertThat(result.parseMode()).isEqualTo(DocumentParseMode.HYBRID);
        assertThat(result.blocks()).extracting(DocumentBlock::page).contains(1, 2);
        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .contains("Emergency command system and response plan", "scanned response");
        assertThat(result.warnings()).anyMatch(message -> message.contains("文本页和扫描页"));
    }
    @Test
    void rotatesAndRecognizesSidewaysResponseTableByColumn() throws Exception {
        Path pdfPath = tempDir.resolve("sideways-response-table.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage(new PDRectangle(612, 792)));
            pdf.save(pdfPath.toFile());
        }
        PlanProperties properties = new PlanProperties();
        properties.getPdf().setOcrDpi(72);
        AtomicInteger attempts = new AtomicInteger();
        PdfDocumentParser parser = new PdfDocumentParser(
                properties, tableEngine(attempts), identityPreprocessor());

        ParsedDocument result = parser.parse(new DownloadedDocument(
                pdfPath,
                "sideways-response-table.pdf",
                "application/pdf",
                java.nio.file.Files.size(pdfPath),
                DocumentFileType.PDF));

        assertThat(result.parseMode()).isEqualTo(DocumentParseMode.OCR);
        assertThat(result.blocks()).extracting(DocumentBlock::text).containsSubsequence(
                "四级响应", "符合以下情形之一时，启动四级响应：", "1. 四级条件内容。",
                "三级响应", "符合以下情形之一时，启动三级响应：", "1. 三级条件内容。",
                "二级响应", "符合以下情形之一时，启动二级响应：", "1. 二级条件内容。",
                "一级响应", "符合以下情形之一时，启动一级响应：", "1. 一级条件内容。");
        assertThat(result.blocks()).allMatch(DocumentBlock::table);
        assertThat(result.blocks())
                .extracting(DocumentBlock::text)
                .doesNotContain("注：测试页注。", "数，‘以下’不包括本数。");
        assertThat(attempts).hasValue(5);
    }

    private ImagePreprocessor identityPreprocessor() {
        ImagePreprocessor preprocessor = mock(ImagePreprocessor.class);
        when(preprocessor.preprocess(
                any(Path.class), anyString(), anyString(), anyLong(), anyBoolean()))
                .thenAnswer(invocation -> {
                    Path image = invocation.getArgument(0);
                    return new PreprocessedImage(
                            image,
                            image,
                            invocation.getArgument(1),
                            "image/png",
                            Files.size(image),
                            612,
                            792,
                            List.of(),
                            List.of(),
                            List.of());
                });
        return preprocessor;
    }
    private OcrEngine tableEngine(AtomicInteger attempts) {
        return new OcrEngine() {
            private final List<String> columns = List.of("四级响应", "三级响应", "二级响应", "一级响应");

            @Override
            public String name() {
                return "fake-table-ocr";
            }

            @Override
            public OcrResult recognize(Path imagePath) {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    return new OcrResult("sideways", List.of(
                            vertical("一级响应", 100, 102),
                            vertical("二级响应", 100, 282),
                            vertical("三级响应", 100, 462),
                            vertical("四级响应", 100, 642)));
                }
                String level = columns.get(attempt - 2);
                String note = attempt == 3 ? "数，‘以下’不包括本数。" : "注：测试页注。";
                return new OcrResult(level, List.of(
                        horizontal(level, 20),
                        horizontal("符合以下情形之一时，启动" + level + "：", 60),
                        horizontal("1. " + level.substring(0, 2) + "条件内容。", 100),
                        horizontal(note, 140)));
            }
        };
    }

    private OcrLine vertical(String text, int centerX, int centerY) {
        return new OcrLine(text, 0.99, List.of(
                new OcrPoint(centerX - 10, centerY - 60),
                new OcrPoint(centerX + 10, centerY - 60),
                new OcrPoint(centerX + 10, centerY + 60),
                new OcrPoint(centerX - 10, centerY + 60)));
    }

    private OcrLine horizontal(String text, int top) {
        return new OcrLine(text, 0.99, List.of(
                new OcrPoint(10, top),
                new OcrPoint(160, top),
                new OcrPoint(160, top + 20),
                new OcrPoint(10, top + 20)));
    }
}
