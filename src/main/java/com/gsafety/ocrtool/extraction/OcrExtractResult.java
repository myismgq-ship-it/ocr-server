package com.gsafety.ocrtool.extraction;

import com.gsafety.ocrtool.recognition.OcrLine;
import java.util.List;
import java.util.Map;
/**
 * 一次模板抽取的完整结果。
 *
 * @param templateCode 实际使用的模板编码
 * @param fields 各字段的值、状态和来源证据
 * @param lowConfidenceFields 低置信度字段名
 * @param missingFields 未识别字段名
 * @param invalidFields 未通过模板格式校验的字段名
 * @param rawText 原始 OCR 全文
 * @param lines 原始 OCR 行及坐标
 */


public record OcrExtractResult(
        String templateCode,
        Map<String, OcrExtractField> fields,
        List<String> lowConfidenceFields,
        List<String> missingFields,
        List<String> invalidFields,
        String rawText,
        List<OcrLine> lines) {

    public OcrExtractResult(
            String templateCode,
            Map<String, OcrExtractField> fields,
            List<String> lowConfidenceFields,
            String rawText,
            List<OcrLine> lines) {
        this(templateCode, fields, lowConfidenceFields, List.of(), List.of(), rawText, lines);
    }
}


