package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTask;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理由人工复核沉淀的准确率样本，以及任务重跑后的回归评测记录。
 *
 * <p>样本不直接参与线上分段，避免一份文档的人工修订意外污染其他预案；
 * 它只用于发现规则改动是否使已确认文档退化。</p>
 */
@Service
public class PlanAccuracyService {

    private final JdbcTemplate jdbc;
    private final PlanDigitizeTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public PlanAccuracyService(
            JdbcTemplate jdbc,
            PlanDigitizeTaskRepository taskRepository,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 将最新人工复核版本设为当前样本，旧样本仅归档不删除，方便追溯人工判断变化。
     */
    @Transactional
    public PlanAccuracySampleResponse registerReviewSample(ReviewResponse review) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("UPDATE plan_accuracy_sample SET status = 'ARCHIVED', archived_at = ? "
                        + "WHERE source_task_id = ? AND status = 'ACTIVE'",
                now, review.taskId());
        UUID sampleId = UUID.randomUUID();
        jdbc.update("INSERT INTO plan_accuracy_sample "
                        + "(sample_id, plan_id, source_task_id, review_id, expected_result, status, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), 'ACTIVE', ?, ?)",
                sampleId,
                review.planId(),
                review.taskId(),
                review.reviewId(),
                write(review.correctedResult()),
                review.reviewerId(),
                now);
        return findSample(sampleId).orElseThrow();
    }

    /** 查询预案下的样本沉淀历史，当前生效样本排在最前。 */
    public List<PlanAccuracySampleResponse> samples(String planId) {
        return jdbc.query("SELECT sample_id, plan_id, source_task_id, review_id, expected_result::text, "
                        + "status, created_by, created_at, archived_at "
                        + "FROM plan_accuracy_sample WHERE plan_id = ? "
                        + "ORDER BY (status = 'ACTIVE') DESC, created_at DESC",
                this::mapSample,
                planId);
    }

    /** 指定任务是否已经有可用于回归评测的当前样本。 */
    public boolean hasActiveSample(UUID sourceTaskId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_accuracy_sample WHERE source_task_id = ? AND status = 'ACTIVE'",
                Integer.class,
                sourceTaskId);
        return count != null && count > 0;
    }

    /**
     * 在重跑任务成功持久化后保存其结构覆盖率。
     * 没有关联样本的普通任务不会生成评测记录。
     */
    @Transactional
    public void evaluateReplayIfApplicable(PlanDigitizeTask replayTask, Map<String, Object> actualResult) {
        if (replayTask.retryOfTaskId() == null || actualResult == null) {
            return;
        }
        Optional<PlanAccuracySampleResponse> sample = activeSample(replayTask.retryOfTaskId());
        if (sample.isEmpty()) {
            return;
        }
        Map<String, Object> summary = PlanAccuracyEvaluator.evaluate(sample.get().expectedResult(), actualResult);
        jdbc.update("INSERT INTO plan_accuracy_evaluation "
                        + "(evaluation_id, sample_id, source_task_id, replay_task_id, summary, created_at) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?) "
                        + "ON CONFLICT (replay_task_id) DO NOTHING",
                UUID.randomUUID(),
                sample.get().sampleId(),
                replayTask.retryOfTaskId(),
                replayTask.taskId(),
                write(summary),
                OffsetDateTime.now());
    }

    /** 查询重跑任务产生的评测结果；未完成或无样本时返回 404。 */
    public PlanAccuracyEvaluationResponse evaluation(String planId, UUID replayTaskId) {
        taskRepository.findByPlanAndTaskId(planId, replayTaskId)
                .orElseThrow(() -> new OcrException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "预案数字化任务不存在。"));
        return jdbc.query("SELECT evaluation_id, sample_id, source_task_id, replay_task_id, summary::text, created_at "
                        + "FROM plan_accuracy_evaluation WHERE replay_task_id = ?",
                this::mapEvaluation,
                replayTaskId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new OcrException(
                        HttpStatus.NOT_FOUND,
                        "TASK_EVALUATION_NOT_FOUND",
                        "该任务尚未生成准确率评测结果。"));
    }

    private Optional<PlanAccuracySampleResponse> activeSample(UUID sourceTaskId) {
        return jdbc.query("SELECT sample_id, plan_id, source_task_id, review_id, expected_result::text, "
                        + "status, created_by, created_at, archived_at "
                        + "FROM plan_accuracy_sample WHERE source_task_id = ? AND status = 'ACTIVE'",
                this::mapSample,
                sourceTaskId).stream().findFirst();
    }

    private Optional<PlanAccuracySampleResponse> findSample(UUID sampleId) {
        return jdbc.query("SELECT sample_id, plan_id, source_task_id, review_id, expected_result::text, "
                        + "status, created_by, created_at, archived_at "
                        + "FROM plan_accuracy_sample WHERE sample_id = ?",
                this::mapSample,
                sampleId).stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private PlanAccuracySampleResponse mapSample(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, Object> expected = objectMapper.readValue(rs.getString(5), Map.class);
            return new PlanAccuracySampleResponse(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class), Map.copyOf(new LinkedHashMap<>(expected)), rs.getString(6),
                    rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class));
        } catch (Exception ex) {
            throw new SQLException("准确率样本读取失败。", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private PlanAccuracyEvaluationResponse mapEvaluation(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, Object> summary = objectMapper.readValue(rs.getString(5), Map.class);
            return new PlanAccuracyEvaluationResponse(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class), Map.copyOf(new LinkedHashMap<>(summary)),
                    rs.getObject(6, OffsetDateTime.class));
        } catch (Exception ex) {
            throw new SQLException("准确率评测结果读取失败。", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "ACCURACY_DATA_INVALID", "准确率数据无法序列化。", ex);
        }
    }
}
