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
            addBodyElements(blocks, doc.getBodyElements());
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
                addParagraph(blocks, paragraph, 0);
            }
        }
        return new ParsedDocument(
                document.fileName(),
                document.fileType(),
                DocumentParseMode.WORD,
                blocks,
                List.of("DOC 格式只能保留基础段落文本，复杂表格结构可能不完整。"));
    }

    private void addBodyElements(List<DocumentBlock> blocks, List<IBodyElement> elements) {
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                addParagraph(blocks, paragraph.getText(), headingLevel(paragraph));
            } else if (element instanceof XWPFTable table) {
                addTable(blocks, table);
            }
        }
    }

    private void addParagraph(List<DocumentBlock> blocks, String text, int styleHeadingLevel) {
        List<String> fragments = DocumentTextNormalizer.split(text);
        for (String fragment : fragments) {
            int inferred = DocumentTextNormalizer.inferHeadingLevel(fragment);
            int headingLevel = inferred > 0
                    ? inferred
                    : fragments.size() == 1 ? styleHeadingLevel : 0;
            blocks.add(new DocumentBlock(fragment, 1, headingLevel, false, List.of()));
        }
    }

    private void addTable(List<DocumentBlock> blocks, XWPFTable table) {
        if (isLayoutTable(table)) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    addBodyElements(blocks, cell.getBodyElements());
                }
            }
            return;
        }
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = row.getTableCells().stream()
                    .map(this::cellText)
                    .filter(StringUtils::hasText)
                    .toList();
            if (!cells.isEmpty()) {
                blocks.add(new DocumentBlock(String.join(" ", cells), 1, 0, true, cells));
            }
        }
    }

    private boolean isLayoutTable(XWPFTable table) {
        int cells = 0;
        int paragraphs = 0;
        int maxColumns = 0;
        for (XWPFTableRow row : table.getRows()) {
            maxColumns = Math.max(maxColumns, row.getTableCells().size());
            for (XWPFTableCell cell : row.getTableCells()) {
                cells++;
                paragraphs += (int) cell.getParagraphs().stream()
                        .filter(paragraph -> StringUtils.hasText(DocumentTextNormalizer.clean(paragraph.getText())))
                        .count();
            }
        }
        return maxColumns <= 1 && paragraphs >= 3 || cells == 1 && paragraphs >= 2;
    }

    private String cellText(XWPFTableCell cell) {
        List<String> fragments = new ArrayList<>();
        for (IBodyElement element : cell.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                fragments.addAll(DocumentTextNormalizer.split(paragraph.getText()));
            } else if (element instanceof XWPFTable nested) {
                for (XWPFTableRow row : nested.getRows()) {
                    row.getTableCells().stream()
                            .map(this::cellText)
                            .filter(StringUtils::hasText)
                            .forEach(fragments::add);
                }
            }
        }
        return String.join("\n", fragments);
    }

    private int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (StringUtils.hasText(style)) {
            Matcher matcher = Pattern.compile("(?:Heading|标题)\\s*(\\d+)").matcher(style);
            if (matcher.find()) {
                return DocumentTextNormalizer.clampHeadingLevel(Integer.parseInt(matcher.group(1)));
            }
        }
        return DocumentTextNormalizer.inferHeadingLevel(paragraph.getText());
    }
}
