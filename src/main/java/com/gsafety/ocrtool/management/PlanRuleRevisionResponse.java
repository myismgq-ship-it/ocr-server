package com.gsafety.ocrtool.management;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 预案规则修订响应。
 *
 * @param revisionId 修订唯一 ID
 * @param revisionNumber 单调递增版本号
 * @param status DRAFT、PUBLISHED 或 ARCHIVED
 * @param rules 当前修订的完整规则清单
 * @param createdBy 创建调用方
 * @param createdAt 创建时间
 * @param publishedAt 发布时间，草稿为空
 */
public record PlanRuleRevisionResponse(
        UUID revisionId,
        int revisionNumber,
        String status,
        List<PlanRuleDefinition> rules,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt) {
}
