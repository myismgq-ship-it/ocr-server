package com.gsafety.ocrtool.recognition;

import com.gsafety.ocrtool.response.OcrBatchItemResponse;
import com.gsafety.ocrtool.response.OcrBatchResponse;
import com.gsafety.ocrtool.response.OcrRecognizeResponse;
import com.gsafety.ocrtool.engine.OcrEngine;
import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.OcrProperties;
import com.gsafety.ocrtool.preprocess.ImagePreprocessor;
import com.gsafety.ocrtool.preprocess.PreprocessedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 通用图片 OCR 编排服务。
 *
 * <p>负责预处理、按质量自适应补跑原图、汇总置信度以及批量任务的单文件故障隔离，
 * 不包含具体证照字段抽取规则。</p>
 */
@Service
public class OcrRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(OcrRecognitionService.class);

    /** 图片校验、增强和临时资源生命周期管理。 */
    private final ImagePreprocessor imagePreprocessor;
    /** 已组合本地/备用策略的 OCR 引擎入口。 */
    private final OcrEngine ocrEngine;
    /** 自适应双通道识别阈值等 OCR 配置。 */
    private final OcrProperties properties;

    public OcrRecognitionService(ImagePreprocessor imagePreprocessor, OcrEngine ocrEngine, OcrProperties properties) {
        this.imagePreprocessor = imagePreprocessor;
        this.ocrEngine = ocrEngine;
        this.properties = properties;
    }

    /**
     * 识别单张图片并返回文本、坐标、置信度和预处理信息。
     *
     * @param file 上传图片
     * @return 产品化 OCR 响应
     */
    public OcrRecognizeResponse recognize(MultipartFile file) {
        long startedAt = System.nanoTime();
        try (PreprocessedImage image = imagePreprocessor.preprocess(file)) {
            OcrResult result = recognizeBestPass(image);
            List<String> warnings = new ArrayList<>(image.warnings());
            if (!StringUtils.hasText(result.text())) {
                warnings.add("未识别到有效文字。");
            }
            Double confidence = averageConfidence(result.lines());
            if (confidence != null && confidence < 0.6) {
                warnings.add("整体置信度较低，建议上传更清晰的图片。");
            }
            return new OcrRecognizeResponse(
                    image.fileName(),
                    result.text(),
                    result.lines(),
                    confidence,
                    image.imageWidth(),
                    image.imageHeight(),
                    ocrEngine.name(),
                    image.steps(),
                    List.copyOf(warnings),
                    elapsedMillis(startedAt));
        }
    }

    public OcrResult recognizeLegacy(MultipartFile file) {
        OcrRecognizeResponse response = recognize(file);
        return new OcrResult(response.text(), response.lines());
    }

    /**
     * 批量识别图片；任意单文件失败只记录在对应结果项中。
     *
     * @param files 待识别图片集合
     * @return 包含成功/失败统计的批量结果
     */
    public OcrBatchResponse recognizeBatch(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "批量识别图片不能为空。");
        }
        List<OcrBatchItemResponse> items = new ArrayList<>();
        int successCount = 0;
        for (MultipartFile file : files) {
            String fileName = file == null ? "unknown" : file.getOriginalFilename();
            try {
                OcrRecognizeResponse result = recognize(file);
                items.add(new OcrBatchItemResponse(result.fileName(), true, result, null, null, null));
                successCount++;
            } catch (OcrException ex) {
                log.warn("批量 OCR 单文件识别失败，fileName={}, code={}, message={}",
                        fileName, ex.getCode(), ex.getMessage());
                items.add(new OcrBatchItemResponse(
                        StringUtils.hasText(fileName) ? fileName : "unknown",
                        false,
                        null,
                        ex.getStatus().value(),
                        ex.getCode(),
                        ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("批量 OCR 单文件识别发生未预期异常，fileName={}", fileName, ex);
                items.add(new OcrBatchItemResponse(
                        StringUtils.hasText(fileName) ? fileName : "unknown",
                        false,
                        null,
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "OCR_ERROR",
                        "OCR 识别失败。"));
            }
        }
        return new OcrBatchResponse(files.length, successCount, files.length - successCount, items);
    }

    private Double averageConfidence(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        return lines.stream()
                .map(OcrLine::confidence)
                .filter(confidence -> confidence != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .stream()
                .boxed()
                .findFirst()
                .orElse(null);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 先识别预处理图，只有结果不足时才补跑原图并选择质量更高的结果。
     */
    private OcrResult recognizeBestPass(PreprocessedImage image) {
        OcrResult preprocessed = ocrEngine.recognize(image.imagePath());
        // 预处理图是主通道，清晰样本只执行一次 OCR，避免固定双通道带来的耗时翻倍。
        if (!shouldRunSupplement(image, preprocessed)) {
            return preprocessed;
        }
        try {
            // 原图仅作为低文本量或低置信度结果的补充通道。
            OcrResult original = ocrEngine.recognize(image.originalImagePath());
            if (score(original) > score(preprocessed)) {
                log.info("OCR 原图识别结果优于预处理图，fileName={}, originalScore={}, preprocessedScore={}",
                        image.fileName(), score(original), score(preprocessed));
                return mergePassText(original, preprocessed);
            }
            return mergePassText(preprocessed, original);
        } catch (RuntimeException ex) {
            log.warn("OCR 原图补充识别失败，继续使用预处理图结果，fileName={}", image.fileName(), ex);
        }
        return preprocessed;
    }

    private boolean shouldRunSupplement(PreprocessedImage image, OcrResult result) {
    /**
     * 根据文本长度和平均置信度判断是否需要补充识别原图。
     */
        if (!properties.getPreprocess().isMultiPass()
                || image.imagePath().equals(image.originalImagePath())) {
            return false;
        }
        int textLength = result.text() == null
                ? 0
                : result.text().replaceAll("\\s+", "").length();
        if (textLength < Math.max(0, properties.getPreprocess().getMultiPassMinTextChars())) {
            return true;
        }
        Double confidence = averageConfidence(result.lines());
        return confidence == null
                || confidence < properties.getPreprocess().getMultiPassMinConfidence();
    }

    private OcrResult mergePassText(OcrResult primary, OcrResult supplement) {
    /**
     * 保留主结果坐标，并按去重后的行文本合并补充通道内容。
     */
        List<String> texts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addUniqueText(texts, seen, primary);
        addUniqueText(texts, seen, supplement);
        String mergedText = String.join("\n", texts);
        return new OcrResult(mergedText, primary.lines());
    }

    private void addUniqueText(List<String> texts, Set<String> seen, OcrResult result) {
        if (result == null || result.lines() == null) {
            return;
        }
        for (OcrLine line : result.lines()) {
            if (line == null || !StringUtils.hasText(line.text())) {
                continue;
            }
            String normalized = line.text().replaceAll("\\s+", "");
            if (StringUtils.hasText(normalized) && seen.add(normalized)) {
                texts.add(line.text());
            }
        }
    }

    private int score(OcrResult result) {
        int lineCount = result.lines() == null ? 0 : result.lines().size();
        int textLength = result.text() == null ? 0 : result.text().replaceAll("\\s+", "").length();
        return lineCount * 20 + textLength;
    }
}
