package com.gsafety.ocrtool.document;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
}
