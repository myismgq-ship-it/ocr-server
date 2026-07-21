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

@Repository
public class PlanDigitizeTaskRepository {

    private static final String COLUMNS = """
            task_id, plan_id, source_type, file_type, file_name, content_type, file_size,
            source_url, source_path, status, result::text AS result_json, error_code,
            error_message, worker_id, heartbeat_at, retry_of_task_id, queued_at,
            started_at, completed_at, created_at, updated_at
            """;
    private static final String HISTORY_COLUMNS = COLUMNS.replace(
            "result::text AS result_json", "NULL::text AS result_json");
    private static final String CLAIMED_COLUMNS = """
            task.task_id AS task_id, task.plan_id AS plan_id, task.source_type AS source_type,
            task.file_type AS file_type, task.file_name AS file_name, task.content_type AS content_type,
            task.file_size AS file_size, task.source_url AS source_url, task.source_path AS source_path,
            task.status AS status, task.result::text AS result_json, task.error_code AS error_code,
            task.error_message AS error_message, task.worker_id AS worker_id,
            task.heartbeat_at AS heartbeat_at, task.retry_of_task_id AS retry_of_task_id,
            task.queued_at AS queued_at, task.started_at AS started_at,
            task.completed_at AS completed_at, task.created_at AS created_at,
            task.updated_at AS updated_at
            """;
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
                heartbeat_at = ?, updated_at = ?
            FROM next_task
            WHERE task.task_id = next_task.task_id
            RETURNING
            """ + CLAIMED_COLUMNS;

    private final JdbcTemplate jdbcTemplate;

    public PlanDigitizeTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(PlanDigitizeTask task) {
        jdbcTemplate.update("""
                INSERT INTO plan_digitize_task (
                    task_id, plan_id, source_type, file_type, file_name, content_type, file_size,
                    source_url, source_path, status, retry_of_task_id, queued_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?)
                """,
                task.taskId(), task.planId(), task.sourceType().name(), task.fileType(), task.fileName(),
                task.contentType(), task.fileSize(), task.sourceUrl(), task.sourcePath(), task.retryOfTaskId(),
                task.queuedAt(), task.createdAt(), task.updatedAt());
    }

    public Optional<PlanDigitizeTask> findActive(String planId) {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE plan_id = ? AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at DESC LIMIT 1",
                this::mapRow,
                planId));
    }

    public Optional<PlanDigitizeTask> findByPlanAndTaskId(String planId, UUID taskId) {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task WHERE plan_id = ? AND task_id = ?",
                this::mapRow,
                planId,
                taskId));
    }

    public Optional<PlanDigitizeTask> findLatest(String planId) {
        return first(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task WHERE plan_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                this::mapRow,
                planId));
    }

    public List<PlanDigitizeTask> findHistory(String planId, int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT " + HISTORY_COLUMNS + " FROM plan_digitize_task WHERE plan_id = ? "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::mapRow,
                planId,
                limit,
                offset);
    }

    public long countHistory(String planId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM plan_digitize_task WHERE plan_id = ?", Long.class, planId);
        return count == null ? 0 : count;
    }

    @Transactional
    public Optional<PlanDigitizeTask> claimNext(String workerId, OffsetDateTime now) {
        return first(jdbcTemplate.query(
                CLAIM_NEXT_SQL,
                this::mapRow,
                workerId,
                now,
                now,
                now));
    }

    public int complete(UUID taskId, String workerId, String resultJson, OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'COMPLETED', result = CAST(? AS jsonb), completed_at = ?,
                    heartbeat_at = ?, updated_at = ?, error_code = NULL, error_message = NULL
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, resultJson, now, now, now, taskId, workerId);
    }

    public int fail(
            UUID taskId, String workerId, String errorCode, String errorMessage, OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'FAILED', error_code = ?, error_message = ?, completed_at = ?,
                    heartbeat_at = ?, updated_at = ?
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, errorCode, errorMessage, now, now, now, taskId, workerId);
    }

    public int requeue(UUID taskId, String workerId) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'QUEUED', worker_id = NULL, heartbeat_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, taskId, workerId);
    }

    public int heartbeat(String workerId, OffsetDateTime now) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task SET heartbeat_at = ?, updated_at = ?
                WHERE status = 'RUNNING' AND worker_id = ?
                """, now, now, workerId);
    }

    public int requeueStale(OffsetDateTime cutoff) {
        return jdbcTemplate.update("""
                UPDATE plan_digitize_task
                SET status = 'QUEUED', worker_id = NULL, heartbeat_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND (heartbeat_at IS NULL OR heartbeat_at < ?)
                """, cutoff);
    }

    public List<PlanDigitizeTask> findExpiredFailedFiles(OffsetDateTime cutoff) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE status = 'FAILED' AND source_path IS NOT NULL AND completed_at < ?",
                this::mapRow,
                cutoff);
    }

    public List<PlanDigitizeTask> findCompletedFilesPendingCleanup() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM plan_digitize_task "
                        + "WHERE status = 'COMPLETED' AND source_path IS NOT NULL",
                this::mapRow);
    }

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
                offsetDateTime(rs, "heartbeat_at"),
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
