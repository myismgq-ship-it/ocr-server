package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.common.ProcessingMetrics;
import com.gsafety.ocrtool.common.ProcessingProgressListener;
import com.gsafety.ocrtool.common.ProcessingStage;
import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.engine.OcrEngine;
import com.gsafety.ocrtool.preprocess.ImagePreprocessor;
import com.gsafety.ocrtool.preprocess.PreprocessedImage;
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

/**
 * 按页解析 PDF 的混合型文档解析器。
 *
 * <p>每页先尝试提取文本；有效文本不足时只对该页渲染并执行 OCR，
 * 从而避免将整份混合 PDF 错误地全部归为文本或扫描件。</p>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);
    private static final List<String> RESPONSE_LEVEL_NAMES =
            List.of("一级响应", "二级响应", "三级响应", "四级响应");

    /** PDF 页数、渲染 DPI 和有效文本阈值。 */
    private final PlanProperties properties;
    /** 实际执行页面文字识别的 OCR 引擎，可能包含降级策略。 */
    private final OcrEngine ocrEngine;
    /** 复用图片入口的像素限制、增强和原生资源释放逻辑。 */
    private final ImagePreprocessor imagePreprocessor;

    public PdfDocumentParser(PlanProperties properties, OcrEngine ocrEngine, ImagePreprocessor imagePreprocessor) {
        this.properties = properties;
        this.ocrEngine = ocrEngine;
        this.imagePreprocessor = imagePreprocessor;
    }

    @Override
    public boolean supports(DocumentFileType fileType) {
        return fileType == DocumentFileType.PDF;
    }

    @Override
    public ParsedDocument parse(DownloadedDocument document) {
        return parse(document, ProcessingProgressListener.NOOP);
    }

    @Override
    /**
     * 解析 PDF 并按处理阶段上报进度。
     *
     * @param document 已下载或上传的 PDF 临时文件
     * @param listener 任务进度监听器，允许为空
     * @return 合并所有页面后的结构化文档块
     */
    public ParsedDocument parse(DownloadedDocument document, ProcessingProgressListener listener) {
        long totalStarted = System.nanoTime();
        ProcessingProgressListener progress = listener == null ? ProcessingProgressListener.NOOP : listener;
        try (PDDocument pdf = Loader.loadPDF(document.path().toFile())) {
            validatePageCount(pdf);
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<DocumentBlock> blocks = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int textPages = 0;
            int ocrPages = 0;
            int pages = pdf.getNumberOfPages();
            for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                int pageNumber = pageIndex + 1;
                long textStarted = System.nanoTime();
                // 页面级决策是混合 PDF 正确性的关键：文本页不做 OCR，扫描页单独补 OCR。
                List<DocumentBlock> pageBlocks = extractTextBlocks(pdf, pageNumber);
                ProcessingMetrics.record("pdf_text_extract", textStarted);
                if (effectiveTextLength(pageBlocks) >= properties.getPdf().getTextMinChars()) {
                    blocks.addAll(pageBlocks);
                    textPages++;
                } else {
                    progress.onProgress(
                    // 只有有效文本不足的页面才进入高成本的渲染和 OCR 流程。
                            ProcessingStage.OCR,
                            Math.min(84, 35 + (int) Math.round((pageIndex * 49.0) / Math.max(1, pages))));
                    blocks.addAll(ocrPage(document, renderer, pageIndex));
                    ocrPages++;
                }
            }
            DocumentParseMode mode = textPages > 0 && ocrPages > 0
                    ? DocumentParseMode.HYBRID
                    : (ocrPages > 0 ? DocumentParseMode.OCR : DocumentParseMode.PDF_TEXT);
            if (ocrPages > 0) {
                warnings.add(mode == DocumentParseMode.HYBRID
                        ? "PDF 同时包含文本页和扫描页，扫描页已逐页使用 OCR 解析。"
                        : "PDF 缺少有效文本，已逐页使用 OCR 解析。");
            }
            log.info(
                    "PDF 逐页解析完成，fileName={}, pages={}, textPages={}, ocrPages={}, mode={}, totalMs={}",
                    document.fileName(), pages, textPages, ocrPages, mode, elapsedMillis(totalStarted));
            return new ParsedDocument(
                    document.fileName(), document.fileType(), mode, List.copyOf(blocks), List.copyOf(warnings));
        } catch (OcrException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_PARSE_FAILED", "PDF 文档解析失败。", ex);
        } finally {
            ProcessingMetrics.record("pdf_total", totalStarted);
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

    private List<DocumentBlock> extractTextBlocks(PDDocument pdf, int page) throws IOException {
        List<DocumentBlock> blocks = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(pdf);
        for (String line : text.split("\\R")) {
            String cleaned = clean(line);
            if (StringUtils.hasText(cleaned)) {
                blocks.add(new DocumentBlock(cleaned, page, inferHeadingLevel(cleaned), false, List.of()));
            }
        }
        return blocks;
    }

    private List<DocumentBlock> ocrPage(
    /**
     * 渲染并识别单个扫描页，临时图片和预处理资源均在本方法结束前释放。
     *
     * @param pageIndex PDFBox 使用的零基页码
     * @return 当前页转换得到的文档块
     */
            DownloadedDocument document,
            PDFRenderer renderer,
            int pageIndex) throws IOException {
        long pageStarted = System.nanoTime();
        long renderStarted = System.nanoTime();
        BufferedImage bufferedImage = renderer.renderImageWithDPI(
                pageIndex, properties.getPdf().getOcrDpi(), ImageType.RGB);
        // BufferedImage 可能占用较大堆内存，用完后必须主动 flush。
        ProcessingMetrics.record("pdf_render", renderStarted);

        Path imagePath = Files.createTempFile("plan-pdf-page-", ".png");
        try {
            long pngStarted = System.nanoTime();
        // 临时 PNG 仅作为当前 OCR 库的文件路径适配层，finally 中保证删除。
            if (!ImageIO.write(bufferedImage, "png", imagePath.toFile())) {
                throw new IOException("没有可用的 PNG 编码器。");
            }
            ProcessingMetrics.record("pdf_png_write", pngStarted);
            long fileSize = Files.size(imagePath);
            try (PreprocessedImage image = imagePreprocessor.preprocess(
                    imagePath,
                    document.fileName() + "-page-" + (pageIndex + 1) + ".png",
                    "image/png",
                    fileSize,
                    true)) {
                long ocrStarted = System.nanoTime();
                OcrResult result = ocrEngine.recognize(image.imagePath());
                ProcessingMetrics.record("ocr", ocrStarted);
                PageLines pageLines = restoreSidewaysResponseTable(
                        document.fileName(), pageIndex + 1, image.imagePath(), result);
                List<DocumentBlock> blocks = new ArrayList<>();
                if (!pageLines.lines().isEmpty()) {
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
                log.info(
                        "PDF OCR 页面完成，fileName={}, page={}, pageTotalMs={}, blocks={}",
                        document.fileName(), pageIndex + 1, elapsedMillis(pageStarted), blocks.size());
                return blocks;
            }
        } finally {
            Files.deleteIfExists(imagePath);
            bufferedImage.flush();
        }
    }

    /**
     * 检测页面 OCR 中竖排分布的四级响应标题，并在旋转后按列重新识别。
     * 未命中或补充识别失败时保留首次 OCR 结果，避免横倒表优化影响普通页面。
     */
    private PageLines restoreSidewaysResponseTable(
            String fileName,
            int page,
            Path recognizedImagePath,
            OcrResult initialResult) {
        List<OcrLine> initialLines = safeLines(initialResult);
        if (!properties.getPdf().isSidewaysTableOcrEnabled()) {
            return new PageLines(initialLines, false);
        }
        BufferedImage recognizedImage = null;
        try {
            recognizedImage = ImageIO.read(recognizedImagePath.toFile());
            if (recognizedImage == null) {
                return new PageLines(initialLines, false);
            }
            Optional<SidewaysTable> detected = detectSidewaysResponseTable(recognizedImage, initialLines);
            if (detected.isEmpty()) {
                return new PageLines(initialLines, false);
            }
            long started = System.nanoTime();
            SidewaysTable table = detected.get();
            BufferedImage rotated = rotateRightAngle(recognizedImage, table.clockwise());
            try {
                List<OcrLine> columnLines = recognizeResponseColumns(
                        rotated, table.columns(), fileName + "-page-" + page);
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
            } finally {
                rotated.flush();
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("PDF 横倒响应表分列 OCR 失败，已回退页面原始 OCR，fileName={}, page={}", fileName, page, ex);
            return new PageLines(initialLines, false);
        } finally {
            if (recognizedImage != null) {
                recognizedImage.flush();
            }
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

    /** 每个表格列单独经过预处理和 OCR，延续普通 PDF 页面的像素与资源限制。 */
    private List<OcrLine> recognizeResponseColumns(
            BufferedImage image, List<ColumnAnchor> columns, String logicalFileName) throws IOException {
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
            try {
                OcrResult columnResult = recognizeImage(
                        columnImage, logicalFileName + "-response-column-" + (i + 1) + ".png");
                result.addAll(trimColumnLines(
                        safeLines(columnResult), columns.get(i).level(), columns.get(i).originalText()));
            } finally {
                columnImage.flush();
            }
        }
        return List.copyOf(result);
    }

    private OcrResult recognizeImage(BufferedImage image, String logicalFileName) throws IOException {
        Path path = Files.createTempFile("plan-pdf-response-column-", ".png");
        try {
            if (!ImageIO.write(image, "png", path.toFile())) {
                throw new IOException("无法写入 PDF OCR 临时图片：" + path);
            }
            try (PreprocessedImage preprocessed = imagePreprocessor.preprocess(
                    path, logicalFileName, "image/png", Files.size(path), true)) {
                long ocrStarted = System.nanoTime();
                OcrResult result = ocrEngine.recognize(preprocessed.imagePath());
                ProcessingMetrics.record("ocr", ocrStarted);
                return result;
            }
        } finally {
            Files.deleteIfExists(path);
        }
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
