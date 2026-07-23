package com.gsafety.ocrtool.management;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 人工复核提交参数。
 *
 * @param correctedResult 人工确认后的完整结构化结果
 * @param note 本次修订说明，可为空
 */

public record ReviewRequest(
        @NotNull Map<String, Object> correctedResult,
        String note) {
}
