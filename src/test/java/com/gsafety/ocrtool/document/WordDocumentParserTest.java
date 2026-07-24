package com.gsafety.ocrtool.document;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WordDocumentParserTest {

    private final WordDocumentParser parser = new WordDocumentParser();

    @TempDir
    Path tempDirectory;

    @Test
    void infersCompactNumberedHeadingAndRemovesBom() throws Exception {
        Path path = tempDirectory.resolve("compact-heading.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(path)) {
            document.createParagraph().createRun().setText("5.1一级响应");
            document.createParagraph().createRun().setText("\uFEFF启动条件");
            document.write(output);
        }
        DownloadedDocument downloaded = new DownloadedDocument(
                path,
                path.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.size(path),
                DocumentFileType.DOCX);

        ParsedDocument result = parser.parse(downloaded);

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().get(0).headingLevel()).isEqualTo(2);
        assertThat(result.blocks().get(1).text()).isEqualTo("启动条件");
    }

    @Test
    void preservesManualBreaksAsOrderedBlocks() throws Exception {
        Path path = tempDirectory.resolve("manual-breaks.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(path)) {
            var run = document.createParagraph().createRun();
            run.setText("5 应急响应");
            run.addBreak();
            run.setText("5.1 Ⅰ级响应");
            run.addBreak();
            run.setText("5.1.1 启动条件");
            document.write(output);
        }

        ParsedDocument result = parser.parse(downloaded(path));

        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .containsExactly("5 应急响应", "5.1 Ⅰ级响应", "5.1.1 启动条件");
        assertThat(result.blocks()).extracting(DocumentBlock::headingLevel)
                .containsExactly(1, 2, 3);
    }

    @Test
    void expandsSingleCellLayoutTableButKeepsDataTableRows() throws Exception {
        Path path = tempDirectory.resolve("tables.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(path)) {
            XWPFTable layout = document.createTable(1, 1);
            var layoutCell = layout.getRow(0).getCell(0);
            layoutCell.getParagraphs().get(0).createRun().setText("4 应急响应");
            layoutCell.addParagraph().createRun().setText("4.1 一级响应");
            layoutCell.addParagraph().createRun().setText("组织救援力量开展处置。");
            XWPFTable data = document.createTable(1, 2);
            data.getRow(0).getCell(0).setText("一级响应");
            data.getRow(0).getCell(1).setText("启动条件一");
            document.write(output);
        }

        ParsedDocument result = parser.parse(downloaded(path));

        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .containsSubsequence("4 应急响应", "4.1 一级响应", "组织救援力量开展处置。");
        assertThat(result.blocks().stream().filter(DocumentBlock::table).toList())
                .singleElement()
                .satisfies(block -> assertThat(block.cells()).containsExactly("一级响应", "启动条件一"));
    }

    @Test
    void normalizesFullWidthAndMisreadNumberingOnlyAtHeadingPrefix() throws Exception {
        Path path = tempDirectory.resolve("numbering.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(path)) {
            document.createParagraph().createRun().setText("４.l.３ 响应措施");
            document.createParagraph().createRun().setText("型号为L.3，不应修改正文。");
            document.write(output);
        }

        ParsedDocument result = parser.parse(downloaded(path));

        assertThat(result.blocks().get(0).text()).isEqualTo("4.1.3 响应措施");
        assertThat(result.blocks().get(0).headingLevel()).isEqualTo(3);
        assertThat(result.blocks().get(1).text()).isEqualTo("型号为L.3，不应修改正文。");
    }

    private DownloadedDocument downloaded(Path path) throws Exception {
        return new DownloadedDocument(
                path,
                path.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                Files.size(path),
                DocumentFileType.DOCX);
    }
}
