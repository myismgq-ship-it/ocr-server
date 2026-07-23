package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 预案目录应用服务。
 *
 * <p>目录保存工作台展示字段；查询时还会补充尚未登记、但已经存在数字化任务的 plan_id，
 * 从而兼容历史调用方直接使用任务接口创建的预案。</p>
 */
@Service
public class PlanCatalogService {

    /** 目录查询同时包含正式目录和未登记任务的只读回退条目。 */
    private static final String LIST_SQL = """
            WITH latest_task AS (
                SELECT DISTINCT ON (plan_id)
                    plan_id, file_name, result ->> 'fileName' AS result_file_name,
                    created_at, updated_at
                FROM plan_digitize_task
                ORDER BY plan_id, updated_at DESC
            ), catalog_with_fallback AS (
                SELECT plan_id, plan_code, plan_name, category, responsible_department,
                       version_label, updated_at
                FROM plan_catalog
                UNION ALL
                SELECT task.plan_id,
                       task.plan_id AS plan_code,
                       COALESCE(NULLIF(regexp_replace(
                           COALESCE(task.result_file_name, task.file_name, ''), '\\.[^.]+$', ''), ''),
                           task.plan_id) AS plan_name,
                       '未分类' AS category,
                       '未配置' AS responsible_department,
                       '未标注' AS version_label,
                       task.updated_at
                FROM latest_task task
                WHERE NOT EXISTS (
                    SELECT 1 FROM plan_catalog catalog WHERE catalog.plan_id = task.plan_id)
            )
            SELECT plan_id, plan_code, plan_name, category, responsible_department,
                   version_label, updated_at
            FROM catalog_with_fallback
            ORDER BY updated_at DESC, plan_name, plan_id
            """;

    /** PostgreSQL JDBC 访问入口。 */
    private final JdbcTemplate jdbc;

    public PlanCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询全部预案目录，最近更新的预案优先显示。 */
    public List<PlanCatalogResponse> list() {
        return jdbc.query(LIST_SQL, this::mapRow);
    }

    /** 新增预案目录；相同 planId 已存在时返回冲突，避免误覆盖现有元数据。 */
    @Transactional
    public PlanCatalogResponse create(PlanCatalogRequest request) {
        String planId = requirePlanId(request == null ? null : request.planId());
        validateRequest(request);
        OffsetDateTime now = OffsetDateTime.now();
        try {
            jdbc.update("""
                    INSERT INTO plan_catalog (
                        plan_id, plan_code, plan_name, category, responsible_department,
                        version_label, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    planId,
                    valueOrDefault(request.code(), planId),
                    request.name().trim(),
                    valueOrDefault(request.category(), "未分类"),
                    valueOrDefault(request.department(), "未配置"),
                    valueOrDefault(request.version(), "未标注"),
                    now,
                    now);
        } catch (DuplicateKeyException ex) {
            throw new OcrException(HttpStatus.CONFLICT, "PLAN_ALREADY_EXISTS", "预案目录已经存在。", ex);
        }
        return get(planId);
    }

    /**
     * 修改已有目录；历史任务可能尚未登记目录，因此使用 upsert 将其正式纳入目录管理。
     */
    @Transactional
    public PlanCatalogResponse save(String planId, PlanCatalogRequest request) {
        String normalizedId = requirePlanId(planId);
        validateRequest(request);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO plan_catalog (
                    plan_id, plan_code, plan_name, category, responsible_department,
                    version_label, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (plan_id) DO UPDATE SET
                    plan_code = EXCLUDED.plan_code,
                    plan_name = EXCLUDED.plan_name,
                    category = EXCLUDED.category,
                    responsible_department = EXCLUDED.responsible_department,
                    version_label = EXCLUDED.version_label,
                    updated_at = EXCLUDED.updated_at
                """,
                normalizedId,
                valueOrDefault(request.code(), normalizedId),
                request.name().trim(),
                valueOrDefault(request.category(), "未分类"),
                valueOrDefault(request.department(), "未配置"),
                valueOrDefault(request.version(), "未标注"),
                now,
                now);
        return get(normalizedId);
    }

    /** 按 planId 读取正式目录条目。 */
    public PlanCatalogResponse get(String planId) {
        return jdbc.query("""
                        SELECT plan_id, plan_code, plan_name, category, responsible_department,
                               version_label, updated_at
                        FROM plan_catalog WHERE plan_id = ?
                        """, this::mapRow, requirePlanId(planId))
                .stream()
                .findFirst()
                .orElseThrow(() -> new OcrException(
                        HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "预案目录不存在。"));
    }

    private PlanCatalogResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PlanCatalogResponse(
                rs.getString("plan_id"),
                rs.getString("plan_code"),
                rs.getString("plan_name"),
                rs.getString("category"),
                rs.getString("responsible_department"),
                rs.getString("version_label"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private void validateRequest(PlanCatalogRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "PLAN_NAME_REQUIRED", "预案名称不能为空。");
        }
    }

    private String requirePlanId(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("[A-Za-z0-9._-]{1,64}")) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PLAN_ID",
                    "预案 ID 只能包含字母、数字、点、下划线和短横线，且不能超过 64 个字符。");
        }
        return value.trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
