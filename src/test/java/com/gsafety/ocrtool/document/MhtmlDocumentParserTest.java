package com.gsafety.ocrtool.document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MhtmlDocumentParserTest {

    @TempDir
    Path tempDirectory;

    @Test
    void parsesHtmlTextWithoutScriptsOrExternalResources() throws Exception {
        String html = "<html><head><script>恶意脚本内容</script><style>p{color:red}</style></head><body>"
                + "<h1>5 应急响应</h1><p>5.1 Ⅰ级响应<br>启动条件</p>"
                + "<img src='https://example.com/tracker.png'></body></html>";
        String source = "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/related; boundary=\"safe-boundary\"\r\n\r\n"
                + "--safe-boundary\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)) + "\r\n"
                + "--safe-boundary--\r\n";
        Path path = tempDirectory.resolve("word-web.doc");
        Files.writeString(path, source, StandardCharsets.ISO_8859_1);
        DownloadedDocument document = new DownloadedDocument(
                path, path.getFileName().toString(), "multipart/related", Files.size(path), DocumentFileType.MHTML);

        ParsedDocument result = new MhtmlDocumentParser().parse(document);

        assertThat(result.blocks()).extracting(DocumentBlock::text)
                .contains("5 应急响应", "5.1 Ⅰ级响应", "启动条件")
                .noneMatch(text -> text.contains("恶意脚本") || text.contains("tracker.png") || text.contains("color:red"));
        assertThat(result.warnings()).singleElement().asString().contains("MHTML");
    }

    @Test
    void acceptsFoldedMultipartBoundaryHeader() throws Exception {
        String source = "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/related;\r\n"
                + "\tboundary=\"folded-boundary\"\r\n\r\n"
                + "--folded-boundary\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "5 应急响应\r\n"
                + "--folded-boundary--\r\n";
        Path path = tempDirectory.resolve("folded.doc");
        Files.writeString(path, source, StandardCharsets.UTF_8);
        DownloadedDocument document = new DownloadedDocument(
                path, path.getFileName().toString(), "multipart/related", Files.size(path), DocumentFileType.MHTML);

        assertThat(new MhtmlDocumentParser().parse(document).blocks())
                .extracting(DocumentBlock::text)
                .contains("5 应急响应");
    }
}
