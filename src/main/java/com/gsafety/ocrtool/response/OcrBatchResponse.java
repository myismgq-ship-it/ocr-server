package com.gsafety.ocrtool.response;

import java.util.List;

public record OcrBatchResponse(
        int total,
        int successCount,
        int failureCount,
        List<OcrBatchItemResponse> items) {
}


