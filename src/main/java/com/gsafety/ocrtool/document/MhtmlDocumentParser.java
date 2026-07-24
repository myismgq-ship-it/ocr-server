package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Parses Word-generated MHTML without loading scripts, images, or remote resources. */
@Component
public class MhtmlDocumentParser implements DocumentParser {

    private static final int MAX_MIME_PARTS = 128;
    private static final long MAX_DECODED_BYTES = 50L * 1024 * 1024;
    private static final Pattern BOUNDARY_PATTERN = Pattern.compile(
            "boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_PATTERN = Pattern.compile(
            "charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.MHTML;
    }

    @Override
    public ParsedDocument parse(DownloadedDocument document) {
        try {
            byte[] source = Files.readAllBytes(document.path());
            MimeEntity root = parseEntity(source);
            String boundary = parameter(root.headers().get("content-type"), BOUNDARY_PATTERN);
            if (!StringUtils.hasText(boundary)) {
                throw parseFailure("MHTML 缺少 multipart boundary。");
            }
            List<MimeEntity> parts = splitParts(root.body(), boundary);
            MimeText selected = selectTextPart(parts);
            if (selected == null) {
                throw parseFailure("MHTML 中没有可解析的文本正文。");
            }
            String text = selected.html() ? htmlToText(selected.text()) : selected.text();
            List<DocumentBlock> blocks = new ArrayList<>();
            for (String fragment : DocumentTextNormalizer.split(text)) {
                blocks.add(new DocumentBlock(
                        fragment, 1, DocumentTextNormalizer.inferHeadingLevel(fragment), false, List.of()));
            }
            if (blocks.isEmpty()) {
                throw parseFailure("MHTML 正文为空。");
            }
            return new ParsedDocument(
                    document.fileName(),
                    document.fileType(),
                    DocumentParseMode.WORD,
                    List.copyOf(blocks),
                    List.of("该 .doc 文件实际为 Word MHTML 网页归档，已按安全文本模式解析。"));
        } catch (OcrException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_PARSE_FAILED", "MHTML 文档解析失败。", ex);
        }
    }

    private List<MimeEntity> splitParts(byte[] body, String boundary) {
        String value = new String(body, StandardCharsets.ISO_8859_1);
        Pattern delimiter = Pattern.compile(
                "(?m)^--" + Pattern.quote(boundary) + "(--)?[ \\t]*\\r?$");
        Matcher matcher = delimiter.matcher(value);
        List<MimeEntity> parts = new ArrayList<>();
        int contentStart = -1;
        while (matcher.find()) {
            if (contentStart >= 0) {
                String part = value.substring(contentStart, matcher.start()).replaceFirst("^[\\r\\n]+", "");
                if (StringUtils.hasText(part)) {
                    if (parts.size() >= MAX_MIME_PARTS) {
                        throw parseFailure("MHTML MIME 分段数量超过限制。");
                    }
                    parts.add(parseEntity(part.getBytes(StandardCharsets.ISO_8859_1)));
                }
            }
            if (matcher.group(1) != null) {
                break;
            }
            contentStart = matcher.end();
        }
        return List.copyOf(parts);
    }

    private MimeText selectTextPart(List<MimeEntity> parts) throws IOException {
        MimeText plain = null;
        long decodedBytes = 0;
        for (MimeEntity part : parts) {
            String contentType = part.headers().getOrDefault("content-type", "text/plain");
            String lowerType = contentType.toLowerCase(Locale.ROOT);
            if (!lowerType.startsWith("text/html") && !lowerType.startsWith("text/plain")) {
                continue;
            }
            byte[] decoded = decodeBody(part.body(), part.headers().get("content-transfer-encoding"));
            decodedBytes += decoded.length;
            if (decodedBytes > MAX_DECODED_BYTES) {
                throw parseFailure("MHTML 解码后正文大小超过限制。");
            }
            Charset charset = resolveCharset(contentType);
            MimeText candidate = new MimeText(new String(decoded, charset), lowerType.startsWith("text/html"));
            if (candidate.html()) {
                return candidate;
            }
            if (plain == null) {
                plain = candidate;
            }
        }
        return plain;
    }

    private byte[] decodeBody(byte[] body, String encoding) throws IOException {
        String value = encoding == null ? "" : encoding.trim().toLowerCase(Locale.ROOT);
        if ("base64".equals(value)) {
            return Base64.getMimeDecoder().decode(body);
        }
        if ("quoted-printable".equals(value)) {
            return decodeQuotedPrintable(body);
        }
        return body;
    }

    private byte[] decodeQuotedPrintable(byte[] source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        for (int index = 0; index < source.length; index++) {
            int value = source[index] & 0xff;
            if (value != '=') {
                output.write(value);
                continue;
            }
            if (index + 1 < source.length && source[index + 1] == '\n') {
                index++;
                continue;
            }
            if (index + 2 < source.length && source[index + 1] == '\r' && source[index + 2] == '\n') {
                index += 2;
                continue;
            }
            if (index + 2 < source.length) {
                int high = Character.digit((char) source[index + 1], 16);
                int low = Character.digit((char) source[index + 2], 16);
                if (high >= 0 && low >= 0) {
                    output.write((high << 4) + low);
                    index += 2;
                    continue;
                }
            }
            output.write(value);
        }
        return output.toByteArray();
    }

    private String htmlToText(String html) throws IOException {
        String safeHtml = html
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<(?:script|style|noscript)\\b[^>]*>.*?</(?:script|style|noscript)\\s*>", " ");
        HtmlTextCallback callback = new HtmlTextCallback();
        new ParserDelegator().parse(new StringReader(safeHtml), callback, true);
        return callback.text();
    }

    private MimeEntity parseEntity(byte[] source) {
        String value = new String(source, StandardCharsets.ISO_8859_1);
        Matcher separator = Pattern.compile("\\r?\\n\\r?\\n").matcher(value);
        if (!separator.find()) {
            throw parseFailure("MHTML MIME 头不完整。");
        }
        Map<String, String> headers = parseHeaders(value.substring(0, separator.start()));
        byte[] body = value.substring(separator.end()).getBytes(StandardCharsets.ISO_8859_1);
        return new MimeEntity(headers, body);
    }

    private Map<String, String> parseHeaders(String headerText) {
        Map<String, String> headers = new LinkedHashMap<>();
        String current = null;
        for (String line : headerText.split("\\r?\\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
                headers.put(current, headers.get(current) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            current = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            headers.put(current, line.substring(colon + 1).trim());
        }
        return headers;
    }

    private String parameter(String value, Pattern pattern) {
        if (value == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) != null ? matcher.group(1) : matcher.group(2) : null;
    }

    private Charset resolveCharset(String contentType) {
        String name = parameter(contentType, CHARSET_PATTERN);
        if (!StringUtils.hasText(name)) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private OcrException parseFailure(String message) {
        return new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_PARSE_FAILED", message);
    }

    private record MimeEntity(Map<String, String> headers, byte[] body) {
    }

    private record MimeText(String text, boolean html) {
    }

    private static final class HtmlTextCallback extends HTMLEditorKit.ParserCallback {

        private final StringBuilder text = new StringBuilder();
        private int ignoredDepth;

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
                ignoredDepth++;
            } else if (ignoredDepth == 0 && isBlock(tag)) {
                lineBreak();
            }
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) {
                ignoredDepth = Math.max(0, ignoredDepth - 1);
            } else if (ignoredDepth == 0 && isBlock(tag)) {
                lineBreak();
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            if (ignoredDepth == 0 && (tag == HTML.Tag.BR || tag == HTML.Tag.HR)) {
                lineBreak();
            }
        }

        @Override
        public void handleText(char[] data, int position) {
            if (ignoredDepth == 0) {
                text.append(data);
            }
        }

        private boolean isBlock(HTML.Tag tag) {
            return tag == HTML.Tag.P || tag == HTML.Tag.DIV || tag == HTML.Tag.LI
                    || tag == HTML.Tag.TR || tag == HTML.Tag.H1 || tag == HTML.Tag.H2
                    || tag == HTML.Tag.H3 || tag == HTML.Tag.H4 || tag == HTML.Tag.H5;
        }

        private void lineBreak() {
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
        }

        private String text() {
            return text.toString();
        }
    }
}
