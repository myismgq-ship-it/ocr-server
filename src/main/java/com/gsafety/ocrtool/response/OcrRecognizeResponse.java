package com.gsafety.ocrtool.response;

import com.gsafety.ocrtool.recognition.OcrLine;
import java.util.List;

public record OcrRecognizeResponse(
        String fileName,
        String text,
        List<OcrLine> lines,
        Double confidence,
        int imageWidth,
        int imageHeight,
        String engine,
        List<String> preprocessSteps,
        List<String> warnings,
        long durationMs) {
}


