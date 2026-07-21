package com.gsafety.ocrtool.extraction;

import com.gsafety.ocrtool.recognition.OcrLine;
import java.util.List;
import java.util.Map;

public record OcrExtractResult(
        String templateCode,
        Map<String, OcrExtractField> fields,
        List<String> lowConfidenceFields,
        String rawText,
        List<OcrLine> lines) {
}


