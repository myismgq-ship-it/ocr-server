package com.gsafety.ocrtool.management;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * OCR 模板修订响应。
 *
 * <p>definition 是该修订的完整不可变定义，publishedAt 仅在发布后存在。</p>
 */
public record TemplateRevisionResponse(
        UUID revisionId,
        String templateCode,
        int revisionNumber,
        String status,
        Map<String, Object> definition,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt) {
}
