package com.gsafety.ocrtool.plan.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预案数字化任务的 JDBC 持久化入口。
 *
 * <p>所有运行中任务写操作都使用领取令牌做条件更新，返回的更新行数代表租约是否仍然有效。</p>
 */
@Repository
public class PlanDigitizeTaskRepository {

    /** 任务详情查询的统一列顺序，必须与 {@link #mapRow(ResultSet, int)} 保持一致。 */
    private static final String COLUMNS = """
            task_id, plan_id, source_type, file_type, file_name, content_type, file_size,
            source_url, source_path, status, result::text AS result_json, error_code,
            error_message, worker_id, claim_token, heartbeat_at, stage, progress_percent,
            attempt, rule_version, retry_of_task_id, queued_at, started_at, completed_at,
            created_at, updated_at
            """;
    /** 历史分页不返回体积较大的结果 JSON，详情接口再按需读取。 */
    private static final String HISTORY_COLUMNS = COLUMNS.replace(
            "result::text AS result_json", "NULL::text AS result_json");
    private static final String CLAIMED_COLUMNS = """
            task.task_id AS task_id, task.plan_id AS plan_id, task.source_type AS source_type,
            task.file_type AS file_type, task.file_name AS file_name, task.content_type AS content_type,
            task.file_size AS file_size, task.source_url AS source_url, task.source_path AS source_path,
            task.status AS status, task.result::text AS result_json, task.error_code AS error_code,
            task.error_message AS error_message, task.worker_id AS worker_id,
            task.claim_token AS claim_token, task.heartbeat_at AS heartbeat_at,
            task.stage AS stage, task.progress_percent AS progress_percent,
            task.attempt AS attempt, task.rule_version AS rule_version,
            task.retry_of_task_id AS retry_of_task_id, task.queued_at AS queued_at,
            task.started_at AS started_at, task.completed_at AS completed_at,
            task.created_at AS created_at, task.updated_at AS updated_at
            """;
    /** 使用 SKIP LOCKED 原子领取最早排队任务，支持多个实例并发扫描。 */
    static final String CLAIM_NEXT_SQL = """
            WITH next_task AS (
                SELECT task_id FROM plan_digitize_task
                WHERE status = 'QUEUED'
                ORDER BY queued_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE plan_digitize_task task
            SET status = 'RUNNING', worker_id = ?, started_at = COALESCE(started_at, ?),
                claim_token = ?, heartbeat_at = ?, stage = 'QUEUED',
                progress_percent = GREATEST(progress_percent, 1),
                attempt = attempt + 1, updated_at = ?
            FROM next_task
            WHERE task.task_id = next_task.task_id
            RETURNING
            """ + CLAIMED_COLUMNS;

    private final JdbcTemplate jdbcTemplate;

    public PlanDigitizeTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新建排队任务。 */
    public void insert(PlanDigitizeTask task) {
        jdbcTemplate.update("""
                INSERT INTO plan_digitize_task (
                    task_id, plan_id, source_type, file_type, file_name, content_type, file_size,
                    source_url, source_path, status, stage, progress_percent, attempt, rule_version,
                    retry_of_task_id, queued_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.taskId(), task.planId(), task.sourceType().name(), task.fileType(), task.fileName(),
                task.contentType(), task.fileSize(), task.sourceUrl(), task.sourcePath(),
                task.stage(), task.progressPercent(), task.attempt(), task.ruleVersion(),
                task.retryOfTaskId(), task.queuedAt(), task.createdAt(), task.updatedAt());
    }

    /** 查询同一预案当前唯一的排队中或执行中任务。 */
    public Optional<PlanDigitizeTask> findActive(String planId) {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE plan_id = ? AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at DESC LIMIT 1",
                this::mapRow,
                planId));
    }

    /** 按预案和任务 ID 查询完整任务详情。 */
    public Optional<PlanDigitizeTask> findByPlanAndTaskId(String planId, UUID taskId) {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task WHERE plan_id = ? AND task_id = ?",
                this::mapRow,
                planId,
                taskId));
    }

    /** 查询预案最新一次任务。 */
    public Optional<PlanDigitizeTask> findLatest(String planId) {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task WHERE plan_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                this::mapRow,
                planId));
    }

    /** 分页查询任务历史，结果字段被刻意排除。 */
    public List<PlanDigitizeTask> findHistory(String planId, int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT " + HISTORY_COLUMNS + " FROM plan_digitize_task WHERE plan_id = ? "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::mapRow,
                planId,
                limit,
                offset);
    }

    /** 统计指定预案的历史任务数。 */
    public long countHistory(String planId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plan_digitize_task WHERE plan_id = ?", Long.class, planId);
        return count == null ? 0 : count;
    }

    /**
     * 原子领取下一条任务并绑定新的 claimToken。
     *
     * @return 当前执行者成功领取的任务；没有可用任务时为空
     */
    @Transactional
    public Optional<PlanDigitizeTask> claimNext(String workerId, UUID claimToken, OffsetDateTime now) {
        return first(jdbcTemplate.query(
                CLAIM_NEXT_SQL,
                this::mapRow,
                workerId,
                now,
                claimToken,
                now,
                now));
    }

    /**
     * 仅在执行者和领取令牌仍匹配时提交结果。
     * @return 1 表示完成成功，0 表示租约已失效
     */
    public int complete(
            UUID taskId,
            String workerId,
            UUID claimToken,
            String resultJson,
            String ruleVersion,
            OffsetDateTime now) {
        // WHERE 条件同时校验状态、workerId 和 claimToken，旧执行者无法覆盖新尝试的结果。
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'COMPLETED', result = CAST(? AS jsonb), completed_at = ?,
                    heartbeat_at = ?, updated_at = ?, stage = 'COMPLETED', progress_percent = 100,
                    rule_version = ?, claim_token = NULL, error_code = NULL, error_message = NULL
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ? AND claim_token = ?
                """, resultJson, now, now, now, ruleVersion, taskId, workerId, claimToken);
    }

    /**
     * 仅在租约有效时写入失败状态。
     * @return 1 表示写入成功，0 表示租约已失效
     */
    public int fail(
            UUID taskId,
            String workerId,
            UUID claimToken,
            String errorCode,
            String errorMessage,
            OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?,
                    heartbeat_at = ?, updated_at = ?, stage = 'FAILED', claim_token = NULL
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ? AND claim_token = ?
                """, errorCode, errorMessage, now, now, now, taskId, workerId, claimToken);
    }

    /** 在提交到执行器失败时，将仍属于当前领取者的任务放回队列。 */
    public int requeue(UUID taskId, String workerId, UUID claimToken) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'QUEUED', worker_id = NULL, claim_token = NULL, heartbeat_at = NULL,
                    stage = 'QUEUED', progress_percent = 0, updated_at = CURRENT_TIMESTAMP
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ? AND claim_token = ?
                """, taskId, workerId, claimToken);
    }

    /** 刷新当前领取尝试的租约心跳。 */
    public int heartbeat(UUID taskId, String workerId, UUID claimToken, OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task SET heartbeat_at = ?, updated_at = ?
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ? AND claim_token = ?
                """, now, now, taskId, workerId, claimToken);
    }

    /** 更新任务阶段和进度，同时刷新心跳；进度在完成前限制为 0 到 99。 */
    public int updateProgress(
            UUID taskId, String workerId, UUID claimToken, String stage, int progressPercent, OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET stage = ?, progress_percent = ?, heartbeat_at = ?, updated_at = ?
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ? AND claim_token = ?
                """, stage, Math.max(0, Math.min(99, progressPercent)), now, now,
                taskId, workerId, claimToken);
    }

    /** 将超过租约期限且没有心跳的运行中任务重新排队。 */
    public int requeueStale(OffsetDateTime cutoff) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'QUEUED', worker_id = NULL, claim_token = NULL, heartbeat_at = NULL,
                    stage = 'QUEUED', progress_percent = 0, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND (heartbeat_at IS NULL OR heartbeat_at < ?)
                """, cutoff);
    }

    /** 取消仍处于排队或运行状态的任务，并使原 claimToken 立即失效。 */
    public int cancel(String planId, UUID taskId, OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'CANCELLED', stage = 'CANCELLED', completed_at = ?,
                    worker_id = NULL, claim_token = NULL, heartbeat_at = NULL, updated_at = ?
                WHERE plan_id = ? AND task_id = ? AND status IN ('QUEUED', 'RUNNING')
                """, now, now, planId, taskId);
    }

    /** 清空超过保留期的成功结果 JSON，保留任务元数据和审计时间。 */
    public int clearExpiredResults(OffsetDateTime cutoff) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task SET result = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'COMPLETED' AND result IS NOT NULL AND completed_at < ?
                """, cutoff);
    }

    /** 查询超过失败文件保留期、等待物理清理的任务。 */
    public List<PlanDigitizeTask> findExpiredFailedFiles(OffsetDateTime cutoff) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE status = 'FAILED' AND source_path IS NOT NULL AND completed_at < ?",
                this::mapRow,
                cutoff);
    }

    /** 查询成功后仍残留源文件的任务，用于补偿清理。 */
    public List<PlanDigitizeTask> findCompletedFilesPendingCleanup() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE status = 'COMPLETED' AND source_path IS NOT NULL",
                this::mapRow);
    }

    /** 查询取消后仍残留源文件的任务，用于补偿清理。 */
    public List<PlanDigitizeTask> findCancelledFilesPendingCleanup() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE status = 'CANCELLED' AND source_path IS NOT NULL",
                this::mapRow);
    }

    /** 查询数据库仍引用的全部任务文件路径，供孤儿文件识别使用。 */
    public List<String> findAllSourcePaths() {
        return jdbcTemplate.queryForList(
                "SELECT source_path FROM plan_digitize_task WHERE source_path IS NOT NULL",
                String.class);
    }

    /** 文件删除成功后清空数据库路径，确保清理操作可重试。 */
    public void clearSourcePath(UUID taskId) {
        jdbcTemplate.update(
                "UPDATE plan_digitize_task SET source_path = NULL, updated_at = CURRENT_TIMESTAMP WHERE task_id = ?",
                taskId);
    }

    private Optional<PlanDigitizeTask> first(List<PlanDigitizeTask> tasks) {
        return tasks.stream().findFirst();
    }

    private PlanDigitizeTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PlanDigitizeTask(
                rs.getObject("task_id", UUID.class),
                rs.getString("plan_id"),
                PlanDigitizeTaskSourceType.valueOf(rs.getString("source_type")),
                rs.getString("file_type"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                nullableLong(rs, "file_size"),
                rs.getString("source_url"),
                rs.getString("source_path"),
                PlanDigitizeTaskStatus.valueOf(rs.getString("status")),
                rs.getString("result_json"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("worker_id"),
                rs.getObject("claim_token", UUID.class),
                offsetDateTime(rs, "heartbeat_at"),
                rs.getString("stage"),
                rs.getInt("progress_percent"),
                rs.getInt("attempt"),
                rs.getString("rule_version"),
                rs.getObject("retry_of_task_id", UUID.class),
                offsetDateTime(rs, "queued_at"),
                offsetDateTime(rs, "started_at"),
                offsetDateTime(rs, "completed_at"),
                offsetDateTime(rs, "created_at"),
                offsetDateTime(rs, "updated_at"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private OffsetDateTime offsetDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }
}
