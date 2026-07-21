package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DocumentDownloadService {

    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final PlanProperties properties;

    public DocumentDownloadService(PlanProperties properties) {
        this.properties = properties;
    }

    public DownloadedDocument download(String documentUrl) {
        URI uri = parseUri(documentUrl);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        Path tempFile = null;
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OcrException(
                        HttpStatus.BAD_REQUEST,
                        "DOCUMENT_DOWNLOAD_FAILED",
                        "文档下载失败，HTTP 状态码：" + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String fileName = resolveFileName(uri, response.headers().firstValue("Content-Disposition"));
            tempFile = Files.createTempFile("plan-document-", ".bin");
            long size = copyWithLimit(response.body(), tempFile);
            DocumentFileType fileType = detectFileType(tempFile, fileName, contentType);
            return new DownloadedDocument(tempFile, fileName, contentType, size, fileType);
        } catch (OcrException ex) {
            deleteQuietly(tempFile);
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            deleteQuietly(tempFile);
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_DOWNLOAD_FAILED", "文档下载失败。", ex);
        }
    }

    private URI parseUri(String documentUrl) {
        if (!StringUtils.hasText(documentUrl)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 不能为空。");
        }
        try {
            URI uri = URI.create(documentUrl.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 只支持 http/https。");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 格式不正确。", ex);
        }
    }

    private long copyWithLimit(InputStream inputStream, Path target) throws IOException {
        long maxSize = properties.getDocument().getMaxSize().toBytes();
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = inputStream; var out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxSize) {
                    throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_TOO_LARGE", "文档大小超过限制。");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private String resolveFileName(URI uri, Optional<String> contentDisposition) {
        if (contentDisposition.isPresent()) {
            Matcher matcher = FILE_NAME_PATTERN.matcher(contentDisposition.get());
            if (matcher.find() && StringUtils.hasText(matcher.group(1))) {
                return sanitizeFileName(matcher.group(1));
            }
        }
        String path = uri.getPath();
        if (StringUtils.hasText(path)) {
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (StringUtils.hasText(name)) {
                return sanitizeFileName(name);
            }
        }
        return "document";
    }

    private String sanitizeFileName(String fileName) {
        String decoded = java.net.URLDecoder.decode(fileName, java.nio.charset.StandardCharsets.UTF_8);
        return decoded.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    DocumentFileType detectFileType(Path path, String fileName, String contentType) throws IOException {
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        byte[] header = readHeader(path, 8);
        if (startsWith(header, "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || lowerName.endsWith(".pdf")
                || lowerContentType.contains("pdf")) {
            return DocumentFileType.PDF;
        }
        if ((header.length >= 4 && header[0] == 'P' && header[1] == 'K')
                || lowerName.endsWith(".docx")
                || lowerContentType.contains("officedocument.wordprocessingml")) {
            return DocumentFileType.DOCX;
        }
        if ((header.length >= 8
                && (header[0] & 0xff) == 0xd0
                && (header[1] & 0xff) == 0xcf
                && (header[2] & 0xff) == 0x11
                && (header[3] & 0xff) == 0xe0)
                || lowerName.endsWith(".doc")
                || lowerContentType.contains("msword")) {
            return DocumentFileType.DOC;
        }
        throw new OcrException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_DOCUMENT_TYPE", "仅支持 Word 和 PDF 文档。");
    }

    private byte[] readHeader(Path path, int size) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(size);
        }
    }

    private boolean startsWith(byte[] header, byte[] prefix) {
        if (header.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (header[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 下载阶段失败时尽力清理临时文件，清理失败不改变对调用方返回的业务错误。
        }
    }
}
