package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.engine.OcrEngine;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                if (result.lines() == null || result.lines().isEmpty()) {
                    continue;
                }
                int page = pageIndex + 1;
                for (OcrLine line : result.lines()) {
                    String cleaned = clean(line.text());
                    if (StringUtils.hasText(cleaned)) {
                        blocks.add(new DocumentBlock(cleaned, page, inferHeadingLevel(cleaned), false, List.of()));
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
}
