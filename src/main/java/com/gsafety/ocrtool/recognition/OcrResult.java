package com.gsafety.ocrtool.recognition;

import java.util.List;

public record OcrResult(
        String text,
        List<OcrLine> lines) {
}

