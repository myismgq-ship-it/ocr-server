package com.gsafety.ocrtool.response;

public record OcrBatchItemResponse(
        String fileName,
        boolean success,
        OcrRecognizeResponse result,
        Integer status,
        String errorCode,
        String message) {
}


