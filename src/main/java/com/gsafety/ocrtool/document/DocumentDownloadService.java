package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.common.ProcessingMetrics;
import com.gsafety.ocrtool.config.PlanProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 从远程 HTTP/HTTPS 地址安全下载预案文档。
 *
 * <p>该服务负责 SSRF 防护、逐次重定向校验、大小限制和真实文件类型识别；
 * 下载得到的临时文件由 {@link DownloadedDocument} 在使用结束时清理。</p>
 */
@Service
public class DocumentDownloadService {

    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?");

    /** 禁止 HttpClient 自动跟随重定向，确保每一个新目标都重新执行 SSRF 校验。 */

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final PlanProperties properties;

    public DocumentDownloadService(PlanProperties properties) {
        this.properties = properties;
    }

    /**
     * 下载并校验一个远程文档。
     *
     * @param documentUrl 调用方提供的文档地址
     * @return 已落盘且完成类型校验的临时文档
     */
    public DownloadedDocument download(String documentUrl) {
        long startedAt = System.nanoTime();
        URI uri = validateTarget(parseUri(documentUrl));
        Path tempFile = null;
        try {
        // 首次请求和后续每次重定向都必须经过同一套目标地址校验。
            int redirects = 0;
            while (true) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (isRedirect(response.statusCode())) {
                // 重定向由应用手动处理，避免 HttpClient 绕过域名、端口或目标 IP 限制。
                    try (InputStream ignored = response.body()) {
                        // 重定向响应体不参与文档解析。
                    }
                    if (redirects++ >= Math.max(0, properties.getDocument().getMaxRedirects())) {
                        throw new OcrException(
                                HttpStatus.BAD_REQUEST,
                                "DOCUMENT_REDIRECT_LIMIT_EXCEEDED",
                                "文档下载重定向次数超过限制。");
                    }
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new OcrException(
                                    HttpStatus.BAD_REQUEST,
                                    "DOCUMENT_DOWNLOAD_FAILED",
                                    "文档下载重定向缺少 Location。"));
                    uri = validateTarget(uri.resolve(location));
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try (InputStream ignored = response.body()) {
                        // 非成功响应体只需要关闭。
                    }
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
                ProcessingMetrics.record("download", startedAt);
                return new DownloadedDocument(tempFile, fileName, contentType, size, fileType);
            }
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

    URI validateTarget(URI uri) {
        String scheme = uri.getScheme();
    /**
     * 校验目标协议、凭据、端口、域名白名单和 DNS 解析出的全部地址。
     *
     * <p>不能只校验 URL 字符串中的主机名：合法域名仍可能解析到内网地址，
     * 因此这里会拒绝任何命中受保护网段的解析结果。</p>
     */
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 只支持 http/https。");
        }
        if (uri.getUserInfo() != null) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 不允许包含用户信息。");
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 缺少有效主机名。");
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
        List<Integer> allowedPorts = properties.getDocument().getAllowedPorts();
        if (port <= 0 || port > 65535
                || allowedPorts != null && !allowedPorts.isEmpty() && !allowedPorts.contains(port)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_TARGET_BLOCKED", "文档 URL 端口不在允许范围内。");
        }
        if (!isAllowedHost(host)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_TARGET_BLOCKED", "文档 URL 主机不在允许范围内。");
        }
        String lookupHost = host.indexOf(':') >= 0 ? host : IDN.toASCII(host);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(lookupHost);
            if (addresses.length == 0) {
                throw new UnknownHostException(host);
            // 校验 DNS 返回的全部地址，防止攻击者通过多 A/AAAA 记录夹带私网目标。
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new OcrException(
                            HttpStatus.BAD_REQUEST,
                            "DOCUMENT_TARGET_BLOCKED",
                            "文档 URL 不允许访问本机或私有网络地址。");
                }
            }
        } catch (UnknownHostException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_HOST_UNRESOLVED", "文档 URL 主机无法解析。", ex);
        }
        return uri;
    }

    private boolean isAllowedHost(String host) {
        List<String> configured = properties.getDocument().getAllowedHosts();
        if (configured == null || configured.isEmpty()) {
            return true;
        }
        String normalizedHost = normalizeHost(host);
        for (String value : configured) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String allowed = normalizeHost(value);
            if (allowed.startsWith("*.")) {
                String suffix = allowed.substring(1);
                if (normalizedHost.endsWith(suffix) && normalizedHost.length() > suffix.length()) {
                    return true;
                }
            } else if (normalizedHost.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHost(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
    /**
     * 判断地址是否属于本机、私网、链路本地、CGNAT、组播或 IPv6 ULA 网段。
     */
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127
                    || first >= 224;
        }
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
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
        byte[] header = readHeader(path, 8);
        if (startsWith(header, "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return DocumentFileType.PDF;
        }
        if (header.length >= 4
                && header[0] == 'P'
                && header[1] == 'K'
                && isDocxPackage(path)) {
            return DocumentFileType.DOCX;
        }
    /**
     * 根据文件魔数和容器结构识别真实类型，不信任扩展名和 Content-Type。
     *
     * @throws IOException 文件头或 ZIP 容器读取失败
     */
        if (header.length >= 8
                && (header[0] & 0xff) == 0xd0
                && (header[1] & 0xff) == 0xcf
                && (header[2] & 0xff) == 0x11
        // PDF、OOXML 和 OLE2 分别使用魔数/必要 ZIP 条目识别，阻止伪造后缀上传。
                && (header[3] & 0xff) == 0xe0) {
            return DocumentFileType.DOC;
        }
        throw new OcrException(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_DOCUMENT_TYPE",
                "文件真实内容不是受支持的 Word 或 PDF 文档。");
    }

    private boolean isDocxPackage(Path path) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
            return zip.getEntry("[Content_Types].xml") != null
                    && zip.getEntry("word/document.xml") != null;
        } catch (IOException ex) {
            return false;
        }
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
