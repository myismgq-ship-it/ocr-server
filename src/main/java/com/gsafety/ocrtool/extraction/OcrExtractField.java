package com.gsafety.ocrtool.extraction;

import com.gsafety.ocrtool.recognition.OcrPoint;
import java.util.List;
import org.springframework.util.StringUtils;
/**
 * 单个模板字段的可追溯抽取结果。
 *
 * @param value 结构化字段值
 * @param confidence 值来源文本的平均置信度
 * @param sourceText 参与拼接的原始 OCR 文本
 * @param status 字段质量状态
 * @param matchedLabel 实际命中的标签别名
 * @param sourceBoxes 来源 OCR 行坐标
 */


public record OcrExtractField(
        String value,
        Double confidence,
        List<String> sourceText,
        OcrExtractStatus status,
        String matchedLabel,
        List<List<OcrPoint>> sourceBoxes) {

    public OcrExtractField(String value, Double confidence, List<String> sourceText) {
        this(
                value,
                confidence,
                sourceText == null ? List.of() : List.copyOf(sourceText),
                StringUtils.hasText(value) ? OcrExtractStatus.EXTRACTED : OcrExtractStatus.MISSING,
                null,
                List.of());
    }

    public OcrExtractField {
        sourceText = sourceText == null ? List.of() : List.copyOf(sourceText);
        status = status == null
                ? (StringUtils.hasText(value) ? OcrExtractStatus.EXTRACTED : OcrExtractStatus.MISSING)
                : status;
        sourceBoxes = sourceBoxes == null ? List.of() : List.copyOf(sourceBoxes);
    }
}


