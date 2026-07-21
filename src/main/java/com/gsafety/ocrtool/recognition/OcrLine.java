package com.gsafety.ocrtool.recognition;

import java.util.List;

public record OcrLine(
        String text,
        Double confidence,
        List<OcrPoint> box) {
}

