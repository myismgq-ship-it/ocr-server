package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTask;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskRepository;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 预案数字化结果的人工复核审计服务。
 *
 * <p>每次提交都新增不可变修订，同时保存机器原始结果和人工修订结果，不覆盖任务结果。</p>
 */
@Service
public class PlanReviewService {

    /** 复核修订表读写入口。 */
    private final JdbcTemplate jdbc;
    /** 用于验证任务归属、状态和结果有效性。 */
    private final PlanDigitizeTaskRepository taskRepository;
    /** 原始/修订结果 JSON 序列化器。 */
    private final ObjectMapper objectMapper;
    /** 将人工确认结果沉淀为可回归评测的准确率样本。*/
    private final PlanAccuracyService accuracyService;

    public PlanReviewService(
            JdbcTemplate jdbc,
            PlanDigitizeTaskRepository taskRepository,
            ObjectMapper objectMapper,
            PlanAccuracyService accuracyService) {
        this.jdbc = jdbc;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.accuracyService = accuracyService;
    }

    /**
     * 为已完成且结果未过期的任务新增复核修订。
     *
     * @param planId 任务所属预案 ID
     * @param taskId 已完成任务 ID
     * @param request 完整修订结果和说明
     * @param reviewerId 网关认证后的复核人标识
     * @return 新增的复核审计记录
     */
    @Transactional
    public ReviewResponse submit(
            String planId,
            UUID taskId,
            ReviewRequest request,
            String reviewerId) {
        PlanDigitizeTask task = completedTask(planId, taskId);
        if (request == null || request.correctedResult() == null) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "REVIEW_RESULT_REQUIRED",
                    "人工复核后的结构化结果不能为空。");
        }
        // 对 taskId 加事务锁，确保同一任务并发提交时修订号仍严格递增。
        lock(taskId);
        int next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_number), 0) + 1 "
                        + "FROM plan_digitize_review WHERE task_id = ?",
                Integer.class,
                taskId);
        UUID reviewId = UUID.randomUUID();
        // original_result 固定取机器任务结果，corrected_result 保存本次人工版本，二者均不可变。
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO plan_digitize_review "
                        + "(review_id, plan_id, task_id, revision_number, original_result, "
                        + "corrected_result, reviewer_id, note, created_at) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)",
                reviewId,
                planId,
                taskId,
                next,
                task.resultJson(),
                write(request.correctedResult()),
                caller(reviewerId),
                request.note(),
                now);
        ReviewResponse review = get(reviewId);
        // 一次复核对应一个不可变样本版本；同源任务的旧样本会归档，保留审计链路。
        accuracyService.registerReviewSample(review);
        return review;
    }

    public List<ReviewResponse> history(String planId, UUID taskId) {
    /** 查询任务的全部人工复核历史，按修订号倒序返回。 */
        completedTask(planId, taskId);
        return jdbc.query(
                "SELECT review_id, plan_id, task_id, revision_number, original_result::text, "
                        + "corrected_result::text, reviewer_id, note, created_at "
                        + "FROM plan_digitize_review WHERE plan_id = ? AND task_id = ? "
                        + "ORDER BY revision_number DESC",
                this::mapRow,
                planId,
                taskId);
    }

    private ReviewResponse get(UUID reviewId) {
        return jdbc.query(
                        "SELECT review_id, plan_id, task_id, revision_number, original_result::text, "
                                + "corrected_result::text, reviewer_id, note, created_at "
                                + "FROM plan_digitize_review WHERE review_id = ?",
                        this::mapRow,
                        reviewId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new OcrException(
                        HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "人工复核记录不存在。"));
    }

    private PlanDigitizeTask completedTask(String planId, UUID taskId) {
    /**
     * 只允许复核已完成且结果 JSON 尚未过期的任务。
     */
        PlanDigitizeTask task = taskRepository.findByPlanAndTaskId(planId, taskId)
                .orElseThrow(() -> new OcrException(
                        HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "预案数字化任务不存在。"));
        if (task.status() != PlanDigitizeTaskStatus.COMPLETED
                || !StringUtils.hasText(task.resultJson())) {
            throw new OcrException(
                    HttpStatus.CONFLICT,
                    "TASK_RESULT_NOT_REVIEWABLE",
                    "只有结果尚未过期的已完成任务可以人工复核。");
        }
        return task;
    }

    @SuppressWarnings("unchecked")
    private ReviewResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, Object> original = objectMapper.readValue(rs.getString(5), Map.class);
            Map<String, Object> corrected = objectMapper.readValue(rs.getString(6), Map.class);
            return new ReviewResponse(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getObject(3, UUID.class),
                    rs.getInt(4),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(original)),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(corrected)),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getObject(9, OffsetDateTime.class));
        } catch (Exception ex) {
            throw new SQLException("人工复核记录读取失败。", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "REVIEW_RESULT_INVALID",
                    "人工复核结果无法序列化。",
                    ex);
        }
    }

    private String caller(String value) {
        return StringUtils.hasText(value) ? value.trim() : "gateway-unknown";
    }
    private void lock(UUID taskId) {
    /**
     * 获取指定任务的事务级 advisory lock，保护修订号分配。
     */
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSetExtractor<Void>) resultSet -> null,
                "plan-review:" + taskId);
    }

}
