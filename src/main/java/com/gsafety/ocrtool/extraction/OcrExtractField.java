package com.gsafety.ocrtool.extraction;

import java.util.List;

public record OcrExtractField(
        String value,
        Double confidence,
        List<String> sourceText) {
}


