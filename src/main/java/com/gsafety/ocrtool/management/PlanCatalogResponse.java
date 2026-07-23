package com.gsafety.ocrtool.management;

import java.time.OffsetDateTime;

/** 工作台使用的预案目录条目。 */
public record PlanCatalogResponse(
        String id,
        String code,
        String name,
        String category,
        String department,
        String version,
        OffsetDateTime updatedAt) {
}
