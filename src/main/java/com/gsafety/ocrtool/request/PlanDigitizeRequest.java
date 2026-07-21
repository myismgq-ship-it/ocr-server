package com.gsafety.ocrtool.request;

import jakarta.validation.constraints.NotBlank;

public record PlanDigitizeRequest(
        @NotBlank(message = "文档 URL 不能为空。")
        String documentUrl) {
}
