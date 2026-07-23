package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class WordDocumentParser implements DocumentParser {

    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十]+[章节篇]|[一二三四五六七八九十]+[、.]|\\d+(?:\\.\\d+){0,3}[、.\\s]).{0,80}");
    /** 阿拉伯数字章节编号，用编号段数还原 DOC 中缺失的标题样式层级。 */
    private static final Pattern ARABIC_NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(?:\\.\\d+){0,5})[、.\\s].{0,80}");

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.DOCX || fileType == DocumentFileType.DOC;
    }

    @Override
    public ParsedDocument parse(DownloadedDocument document) {
        try {
            if (document.fileType() == DocumentFileType.DOCX) {
                return parseDocx(document);
            }
            return parseDoc(document);
        } catch (IOException | RuntimeException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_PARSE_FAILED", "Word 文档解析失败。", ex);
        }
    }

    private ParsedDocument parseDocx(DownloadedDocument document) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (InputStream in = java.nio.file.Files.newInputStream(document.path());
             XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    addParagraph(blocks, paragraph.getText(), headingLevel(paragraph));
                } else if (element instanceof XWPFTable table) {
                    addTable(blocks, table);
                }
            }
        }
        return new ParsedDocument(
                document.fileName(),
                document.fileType(),
                DocumentParseMode.WORD,
                blocks,
                List.of());
    }

    private ParsedDocument parseDoc(DownloadedDocument document) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        try (InputStream in = java.nio.file.Files.newInputStream(document.path());
             HWPFDocument hwpf = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(hwpf)) {
            for (String paragraph : extractor.getParagraphText()) {
                addParagraph(blocks, paragraph, inferHeadingLevel(clean(paragraph)));
            }
        }
        return new ParsedDocument(
                document.fileName(),
                document.fileType(),
                DocumentParseMode.WORD,
                blocks,
                List.of("DOC 格式只能保留基础段落文本，复杂表格结构可能不完整。"));
    }

    private void addParagraph(List<DocumentBlock> blocks, String text, int headingLevel) {
        String cleaned = clean(text);
        if (StringUtils.hasText(cleaned)) {
            blocks.add(new DocumentBlock(cleaned, 1, headingLevel, false, List.of()));
        }
    }

    private void addTable(List<DocumentBlock> blocks, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = row.getTableCells().stream()
                    .map(XWPFTableCell::getText)
                    .map(this::clean)
                    .filter(StringUtils::hasText)
                    .toList();
            if (!cells.isEmpty()) {
                blocks.add(new DocumentBlock(String.join(" ", cells), 1, 0, true, cells));
            }
        }
    }

    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (StringUtils.hasText(style)) {
            Matcher matcher = Pattern.compile("(?:Heading|标题)\\s*(\\d+)").matcher(style);
            if (matcher.find()) {
                return clampHeadingLevel(Integer.parseInt(matcher.group(1)));
            }
        }
        return inferHeadingLevel(clean(paragraph.getText()));
    }

    private int inferHeadingLevel(String text) {
        if (!StringUtils.hasText(text) || text.length() > 80) {
            return 0;
        }
        if (text.matches("^第[一二三四五六七八九十]+[章节篇].*")) {
            return 1;
        }
        Matcher arabic = ARABIC_NUMBERED_HEADING.matcher(text);
        if (arabic.matches()) {
            // 5、5.2、5.2.1 分别对应一级、二级、三级标题。
            int level = 1 + (int) arabic.group(1).chars().filter(value -> value == '.').count();
            return clampHeadingLevel(level);
        }
        if (NUMBERED_HEADING.matcher(text).matches()) {
            return 1;
        }
        return 0;
    }

    private int clampHeadingLevel(int level) {
        return Math.max(1, Math.min(level, 6));
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
