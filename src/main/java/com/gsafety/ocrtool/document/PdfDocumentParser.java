package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.engine.OcrEngine;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrPoint;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);
    private static final List<String> RESPONSE_LEVEL_NAMES =
            List.of("一级响应", "二级响应", "三级响应", "四级响应");

    private final PlanProperties properties;
    private final OcrEngine ocrEngine;

    public PdfDocumentParser(PlanProperties properties, OcrEngine ocrEngine) {
        this.properties = properties;
        this.ocrEngine = ocrEngine;
    }

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.PDF;
    }

    @Override
    public ParsedDocument parse(DownloadedDocument document) {
        long totalStarted = System.nanoTime();
        try (PDDocument pdf = Loader.loadPDF(document.path().toFile())) {
            validatePageCount(pdf);
            long textStarted = System.nanoTime();
            List<DocumentBlock> textBlocks = extractTextBlocks(pdf);
            long textMillis = elapsedMillis(textStarted);
            if (effectiveTextLength(textBlocks) >= properties.getPdf().getTextMinChars()) {
                log.info(
                        "PDF 文本解析完成，fileName={}, pages={}, textMs={}, totalMs={}",
                        document.fileName(), pdf.getNumberOfPages(), textMillis, elapsedMillis(totalStarted));
                return new ParsedDocument(
                        document.fileName(),
                        document.fileType(),
                        DocumentParseMode.PDF_TEXT,
                        textBlocks,
                        List.of());
            }
            ParsedDocument parsed = parseByOcr(document, pdf);
            log.info(
                    "PDF OCR 解析完成，fileName={}, pages={}, textProbeMs={}, totalMs={}",
                    document.fileName(), pdf.getNumberOfPages(), textMillis, elapsedMillis(totalStarted));
            return parsed;
        } catch (OcrException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_PARSE_FAILED", "PDF 文档解析失败。", ex);
        }
    }

    private void validatePageCount(PDDocument pdf) {
        int pages = pdf.getNumberOfPages();
        if (pages > properties.getPdf().getMaxPages()) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "PDF_PAGE_LIMIT_EXCEEDED",
                    "PDF 页数超过限制，当前页数：" + pages);
        }
    }

    private List<DocumentBlock> extractTextBlocks(PDDocument pdf) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(pdf);
            for (String line : text.split("\\R")) {
                String cleaned = clean(line);
                if (StringUtils.hasText(cleaned)) {
                    blocks.add(new DocumentBlock(cleaned, page, inferHeadingLevel(cleaned), false, List.of()));
                }
            }
        }
        return blocks;
    }

    private ParsedDocument parseByOcr(DownloadedDocument document, PDDocument pdf) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<Path> tempImages = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(pdf);
        try {
            for (int pageIndex = 0; pageIndex < pdf.getNumberOfPages(); pageIndex++) {
                long pageStarted = System.nanoTime();
                long renderStarted = System.nanoTime();
                BufferedImage image = renderer.renderImageWithDPI(
                        pageIndex,
                        properties.getPdf().getOcrDpi(),
                        ImageType.RGB);
                long renderMillis = elapsedMillis(renderStarted);
                Path imagePath = Files.createTempFile("plan-pdf-page-", ".png");
                tempImages.add(imagePath);
                long pngStarted = System.nanoTime();
                ImageIO.write(image, "png", imagePath.toFile());
                long pngMillis = elapsedMillis(pngStarted);
                long ocrStarted = System.nanoTime();
                OcrResult result = ocrEngine.recognize(imagePath);
                long ocrMillis = elapsedMillis(ocrStarted);
                log.info(
                        "PDF OCR 页面完成，fileName={}, page={}, renderMs={}, pngMs={}, ocrMs={}, pageTotalMs={}",
                        document.fileName(), pageIndex + 1, renderMillis, pngMillis, ocrMillis,
                        elapsedMillis(pageStarted));
                PageLines pageLines = restoreSidewaysResponseTable(
                        document.fileName(), pageIndex + 1, image, result, tempImages);
                if (pageLines.lines().isEmpty()) {
                    continue;
                }
                int page = pageIndex + 1;
                for (OcrLine line : pageLines.lines()) {
                    String cleaned = clean(line.text());
                    if (StringUtils.hasText(cleaned)) {
                        blocks.add(new DocumentBlock(
                                cleaned,
                                page,
                                inferHeadingLevel(cleaned),
                                pageLines.table(),
                                pageLines.table() ? List.of(cleaned) : List.of()));
                    }
                }
            }
        } finally {
            deleteTempImages(tempImages);
        }
        return new ParsedDocument(
                document.fileName(),
                document.fileType(),
                DocumentParseMode.OCR,
                blocks,
                List.of("PDF 为扫描件或不可直接抽取文本，已使用 OCR 解析，建议前端展示来源页供人工核对。"));
    }

    private PageLines restoreSidewaysResponseTable(
            String fileName,
            int page,
            BufferedImage image,
            OcrResult initialResult,
            List<Path> tempImages) {
        List<OcrLine> initialLines = safeLines(initialResult);
        if (!properties.getPdf().isSidewaysTableOcrEnabled()) {
            return new PageLines(initialLines, false);
        }
        Optional<SidewaysTable> detected = detectSidewaysResponseTable(image, initialLines);
        if (detected.isEmpty()) {
            return new PageLines(initialLines, false);
        }
        long started = System.nanoTime();
        SidewaysTable table = detected.get();
        try {
            BufferedImage rotated = rotateRightAngle(image, table.clockwise());
            List<OcrLine> columnLines = recognizeResponseColumns(rotated, table.columns(), tempImages);
            if (columnLines.isEmpty()) {
                return new PageLines(initialLines, false);
            }
            log.info(
                    "PDF 横倒响应表分列 OCR 完成，fileName={}, page={}, rotation={}, columns={}, tableOcrMs={}",
                    fileName,
                    page,
                    table.clockwise() ? 90 : 270,
                    table.columns().size(),
                    elapsedMillis(started));
            return new PageLines(columnLines, true);
        } catch (IOException | RuntimeException ex) {
            log.warn(
                    "PDF 横倒响应表分列 OCR 失败，已回退页面原始 OCR，fileName={}, page={}",
                    fileName,
                    page,
                    ex);
            return new PageLines(initialLines, false);
        }
    }

    private Optional<SidewaysTable> detectSidewaysResponseTable(
            BufferedImage image, List<OcrLine> lines) {
        Map<String, LineBox> anchors = new LinkedHashMap<>();
        for (String level : RESPONSE_LEVEL_NAMES) {
            lines.stream()
                    .filter(line -> matchesLevel(line.text(), level))
                    .map(line -> new LineBox(line, box(line)))
                    .filter(candidate -> candidate.box() != null)
                    .min(Comparator.comparingInt(candidate -> normalized(candidate.line().text()).length()))
                    .ifPresent(candidate -> anchors.put(level, candidate));
        }
        if (anchors.size() != RESPONSE_LEVEL_NAMES.size()) {
            return Optional.empty();
        }
        long verticalAnchors = anchors.values().stream()
                .filter(anchor -> anchor.box().height() > anchor.box().width() * 1.5)
                .count();
        if (verticalAnchors < 3) {
            return Optional.empty();
        }

        double averageX = anchors.values().stream()
                .mapToDouble(anchor -> anchor.box().centerX())
                .average()
                .orElse(image.getWidth() / 2.0);
        boolean clockwise = averageX <= image.getWidth() / 2.0;
        List<ColumnAnchor> columns = new ArrayList<>();
        for (Map.Entry<String, LineBox> entry : anchors.entrySet()) {
            Box box = entry.getValue().box();
            double transformedX = clockwise ? image.getHeight() - box.centerY() : box.centerY();
            double transformedY = clockwise ? box.centerX() : image.getWidth() - box.centerX();
            columns.add(new ColumnAnchor(entry.getKey(), entry.getValue().line().text(), transformedX, transformedY));
        }
        columns.sort(Comparator.comparingDouble(ColumnAnchor::centerX));
        double spread = columns.get(columns.size() - 1).centerX() - columns.get(0).centerX();
        int rotatedWidth = image.getHeight();
        if (spread < rotatedWidth * 0.45 || !hasUsableColumnGaps(columns, rotatedWidth)) {
            return Optional.empty();
        }
        double minY = columns.stream().mapToDouble(ColumnAnchor::centerY).min().orElse(0);
        double maxY = columns.stream().mapToDouble(ColumnAnchor::centerY).max().orElse(0);
        if (maxY - minY > image.getWidth() * 0.12) {
            return Optional.empty();
        }
        return Optional.of(new SidewaysTable(clockwise, List.copyOf(columns)));
    }

    private boolean hasUsableColumnGaps(List<ColumnAnchor> columns, int width) {
        for (int i = 1; i < columns.size(); i++) {
            if (columns.get(i).centerX() - columns.get(i - 1).centerX() < width * 0.08) {
                return false;
            }
        }
        return true;
    }

    private List<OcrLine> recognizeResponseColumns(
            BufferedImage image, List<ColumnAnchor> columns, List<Path> tempImages) throws IOException {
        int columnCount = columns.size();
        double[] edges = new double[columnCount + 1];
        edges[0] = Math.max(0, columns.get(0).centerX()
                - (columns.get(1).centerX() - columns.get(0).centerX()) / 2.0);
        for (int i = 1; i < columnCount; i++) {
            edges[i] = (columns.get(i - 1).centerX() + columns.get(i).centerX()) / 2.0;
        }
        edges[columnCount] = Math.min(image.getWidth(), columns.get(columnCount - 1).centerX()
                + (columns.get(columnCount - 1).centerX() - columns.get(columnCount - 2).centerX()) / 2.0);

        double headerY = columns.stream().mapToDouble(ColumnAnchor::centerY).average().orElse(0);
        int top = Math.max(0, (int) Math.floor(headerY - image.getHeight() * 0.06));
        List<OcrLine> result = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            int left = Math.max(0, (int) Math.floor(edges[i]));
            int right = Math.min(image.getWidth(), (int) Math.ceil(edges[i + 1]));
            if (right - left < 2 || image.getHeight() - top < 2) {
                continue;
            }
            BufferedImage columnImage = image.getSubimage(left, top, right - left, image.getHeight() - top);
            OcrResult columnResult = recognizeImage(columnImage, tempImages, "plan-pdf-response-column-");
            result.addAll(trimColumnLines(
                    safeLines(columnResult), columns.get(i).level(), columns.get(i).originalText()));
        }
        return List.copyOf(result);
    }

    private List<OcrLine> trimColumnLines(List<OcrLine> lines, String level, String fallbackHeading) {
        List<OcrLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator
                .comparingInt((OcrLine line) -> box(line) == null ? Integer.MAX_VALUE : box(line).top())
                .thenComparingInt(line -> box(line) == null ? Integer.MAX_VALUE : box(line).left()));
        int headingIndex = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (matchesLevel(ordered.get(i).text(), level)) {
                headingIndex = i;
                break;
            }
        }
        List<OcrLine> result = new ArrayList<>();
        if (headingIndex < 0) {
            result.add(new OcrLine(fallbackHeading, null, List.of()));
            headingIndex = 0;
        }
        for (int i = headingIndex; i < ordered.size(); i++) {
            OcrLine line = ordered.get(i);
            if (isPageNote(line.text())) {
                break;
            }
            result.add(line);
        }
        return result;
    }

    private OcrResult recognizeImage(BufferedImage image, List<Path> tempImages, String prefix) throws IOException {
        Path path = Files.createTempFile(prefix, ".png");
        tempImages.add(path);
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IOException("无法写入 PDF OCR 临时图片：" + path);
        }
        return ocrEngine.recognize(path);
    }

    private BufferedImage rotateRightAngle(BufferedImage source, boolean clockwise) {
        BufferedImage target = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (clockwise) {
                graphics.translate(source.getHeight(), 0);
                graphics.rotate(Math.PI / 2);
            } else {
                graphics.translate(0, source.getWidth());
                graphics.rotate(-Math.PI / 2);
            }
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private List<OcrLine> safeLines(OcrResult result) {
        return result == null || result.lines() == null ? List.of() : result.lines();
    }

    private boolean matchesLevel(String text, String level) {
        return normalized(text).contains(level);
    }

    private String normalized(String text) {
        return clean(text).replaceAll("\\s+", "").replace("应急", "");
    }

    private boolean isPageNote(String text) {
        String value = normalized(text);
        return value.startsWith("注：")
                || value.startsWith("注:")
                || (value.contains("以上") && value.contains("包括本数"))
                || (value.contains("以下") && value.contains("不包括本数"));
    }

    private Box box(OcrLine line) {
        if (line == null || line.box() == null || line.box().isEmpty()) {
            return null;
        }
        int left = line.box().stream().mapToInt(OcrPoint::x).min().orElse(0);
        int right = line.box().stream().mapToInt(OcrPoint::x).max().orElse(left);
        int top = line.box().stream().mapToInt(OcrPoint::y).min().orElse(0);
        int bottom = line.box().stream().mapToInt(OcrPoint::y).max().orElse(top);
        return new Box(left, top, right, bottom);
    }

    private int effectiveTextLength(List<DocumentBlock> blocks) {
        return blocks.stream()
                .map(DocumentBlock::text)
                .map(text -> text.replaceAll("\\s+", ""))
                .mapToInt(String::length)
                .sum();
    }

    private int inferHeadingLevel(String text) {
        if (!StringUtils.hasText(text) || text.length() > 80) {
            return 0;
        }
        if (text.matches("^第[一二三四五六七八九十]+[章节篇].*")) {
            return 1;
        }
        if (text.matches("^([一二三四五六七八九十]+[、.]|\\d+(?:\\.\\d+){0,3}[、.\\s]).{1,80}")) {
            return text.matches("^\\d+\\.\\d+.*") ? 2 : 1;
        }
        return 0;
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

    private void deleteTempImages(List<Path> tempImages) {
        for (Path tempImage : tempImages) {
            try {
                Files.deleteIfExists(tempImage);
            } catch (IOException ex) {
                log.warn("PDF OCR 临时图片清理失败，path={}", tempImage, ex);
            }
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record PageLines(List<OcrLine> lines, boolean table) {
    }

    private record LineBox(OcrLine line, Box box) {
    }

    private record SidewaysTable(boolean clockwise, List<ColumnAnchor> columns) {
    }

    private record ColumnAnchor(String level, String originalText, double centerX, double centerY) {
    }

    private record Box(int left, int top, int right, int bottom) {

        private int width() {
            return Math.max(1, right - left);
        }

        private int height() {
            return Math.max(1, bottom - top);
        }

        private double centerX() {
            return (left + right) / 2.0;
        }

        private double centerY() {
            return (top + bottom) / 2.0;
        }
    }
}
