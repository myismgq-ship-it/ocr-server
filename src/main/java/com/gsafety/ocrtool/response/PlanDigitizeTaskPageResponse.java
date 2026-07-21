package com.gsafety.ocrtool.response;

import java.util.List;

public record PlanDigitizeTaskPageResponse(
        List<PlanDigitizeTaskResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
