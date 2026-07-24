package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentDownloadServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsWordMhtmlByMimeHeadersInsteadOfDocExtension() throws Exception {
        Path path = tempDir.resolve("web-archive.doc");
        Files.writeString(path, "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/related; boundary=abc\r\n\r\n"
                + "--abc--\r\n", StandardCharsets.ISO_8859_1);
        DocumentDownloadService service = new DocumentDownloadService(new PlanProperties());

        assertThat(service.detectFileType(path, path.getFileName().toString(), "application/msword"))
                .isEqualTo(DocumentFileType.MHTML);
    }

    @Test
    void blocksIpv4AndIpv6PrivateTargets() throws Exception {
        for (String address : List.of(
                "0.0.0.0",
                "10.0.0.1",
                "100.64.0.1",
                "127.0.0.1",
                "169.254.1.1",
                "172.16.0.1",
                "192.168.1.1",
                "224.0.0.1",
                "::1",
                "fe80::1",
                "fc00::1")) {
            assertThat(DocumentDownloadService.isBlockedAddress(InetAddress.getByName(address)))
                    .as(address)
                    .isTrue();
        }
        assertThat(DocumentDownloadService.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(DocumentDownloadService.isBlockedAddress(
                InetAddress.getByName("2001:4860:4860::8888"))).isFalse();
    }

    @Test
    void validatesHostAllowListAndPortsBeforeConnecting() {
        PlanProperties properties = new PlanProperties();
        properties.getDocument().setAllowedHosts(List.of("downloads.example.test", "*.trusted.example"));
        properties.getDocument().setAllowedPorts(List.of(443));
        DocumentDownloadService service = new DocumentDownloadService(properties);

        assertThatThrownBy(() -> service.validateTarget(URI.create("https://example.com/plan.pdf")))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).getCode())
                .isEqualTo("DOCUMENT_TARGET_BLOCKED");
        assertThatThrownBy(() -> service.validateTarget(
                URI.create("https://downloads.example.test:8443/plan.pdf")))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).getCode())
                .isEqualTo("DOCUMENT_TARGET_BLOCKED");
        assertThatThrownBy(() -> service.validateTarget(URI.create("https://user@example.com/plan.pdf")))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).getCode())
                .isEqualTo("INVALID_DOCUMENT_URL");
    }

    @Test
    void rejectsForgedExtensionAndAcceptsRealContent() throws Exception {
        DocumentDownloadService service = new DocumentDownloadService(new PlanProperties());
        Path fakePdf = tempDir.resolve("fake.pdf");
        Files.writeString(fakePdf, "not a pdf", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.detectFileType(fakePdf, "fake.pdf", "application/pdf"))
                .isInstanceOf(OcrException.class)
                .extracting(error -> ((OcrException) error).getCode())
                .isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");

        Path pdf = tempDir.resolve("real.bin");
        Files.writeString(pdf, "%PDF-1.7\n", StandardCharsets.US_ASCII);
        assertThat(service.detectFileType(pdf, "real.bin", "application/octet-stream"))
                .isEqualTo(DocumentFileType.PDF);

        Path docx = tempDir.resolve("real.docx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(docx))) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(service.detectFileType(docx, "real.docx", "application/zip"))
                .isEqualTo(DocumentFileType.DOCX);
    }
}
