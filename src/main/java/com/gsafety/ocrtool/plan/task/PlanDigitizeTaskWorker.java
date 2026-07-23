package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.management.PlanAccuracyService;
import com.gsafety.ocrtool.common.ProcessingMetrics;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.common.ProcessingProgressListener;
import com.gsafety.ocrtool.document.DownloadedDocument;
import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 执行一次已经领取的预案数字化任务。
 *
 * <p>任务状态、进度和结果写入均受 claimToken 保护，失去租约后立即停止后续持久化和文件清理。</p>
 */
@Service
public class PlanDigitizeTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(PlanDigitizeTaskWorker.class);

    /** 同步文档解析和分段编排服务。 */
    private final PlanDigitizeService digitizeService;
    /** 提供带领取令牌条件的任务状态更新。 */
    private final PlanDigitizeTaskRepository repository;
    /** 管理上传任务源文件，只有成功持久化结果后才删除。 */
    private final PlanTaskStorageService storageService;
    private final ObjectMapper objectMapper;
    /** 重跑完成后保存与人工样本的结构覆盖率。*/
    private final PlanAccuracyService accuracyService;

    public PlanDigitizeTaskWorker(
            PlanDigitizeService digitizeService,
            PlanDigitizeTaskRepository repository,
            PlanTaskStorageService storageService,
            ObjectMapper objectMapper,
            PlanAccuracyService accuracyService) {
        this.digitizeService = digitizeService;
        this.repository = repository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.accuracyService = accuracyService;
    }

    /**
     * 执行任务并提交结果或失败状态。
     *
     * @param task 带有本次 claimToken 的任务快照
     * @param workerId 当前进程实例标识
     */
    public void execute(PlanDigitizeTask task, String workerId) {
        long started = System.nanoTime();
        if (task.claimToken() == null) {
        // 没有领取令牌的任务无法证明归属，禁止执行。
            log.error("任务缺少领取令牌，拒绝执行，taskId={}", task.taskId());
            return;
        }
        ProcessingProgressListener progressListener = (stage, progressPercent) -> {
            int updated = repository.updateProgress(
        // 每次阶段更新同时承担续租作用；更新 0 行表示任务已取消或被其他尝试重新领取。
                    task.taskId(),
                    workerId,
                    task.claimToken(),
                    stage.name(),
                    progressPercent,
                    OffsetDateTime.now());
            if (updated == 0) {
                throw new LeaseLostException();
            }
        };
        try {
            PlanDigitizeResponse result = task.sourceType() == PlanDigitizeTaskSourceType.URL
                    ? digitizeService.digitize(task.sourceUrl(), progressListener)
            // 先序列化并写入数据库，只有受保护的完成更新成功后才能宣布完成。
                    : digitizeUpload(task, progressListener);
            String json = objectMapper.writeValueAsString(result);
            long persistStarted = System.nanoTime();
            int updated = repository.complete(
                    task.taskId(),
                    workerId,
                    task.claimToken(),
                    json,
                    result.ruleVersion(),
            // 更新 0 行时必须保留源文件，新的合法执行者可能仍在使用它。
                    OffsetDateTime.now());
            ProcessingMetrics.record("persist", persistStarted);
            if (updated == 0) {
                log.warn("任务完成时领取令牌已失效，结果未写入，taskId={}", task.taskId());
                return;
            // 上传源文件在结果成功提交后删除，并随后清空数据库路径，支持补偿重试。
            }
            // 成功任务源文件按完成文件保留期保存，供人工复核后的历史回归重跑使用。
            // 到期清理由调度器统一执行，避免评测期间误删源文件。
            evaluateReplay(task, json);
            log.info(
                    "预案数字化任务完成，taskId={}, planId={}, durationMs={}",
                    task.taskId(), task.planId(), elapsedMillis(started));
        } catch (LeaseLostException ex) {
            log.warn("预案数字化任务已取消或领取令牌失效，停止写入，taskId={}", task.taskId());
        } catch (OcrException ex) {
            fail(task, workerId, ex.getCode(), ex.getMessage(), ex, started);
        } catch (Exception ex) {
            fail(task, workerId, "TASK_EXECUTION_FAILED", "预案数字化任务执行失败。", ex, started);
        }
    }

    @SuppressWarnings("unchecked")
    private void evaluateReplay(PlanDigitizeTask task, String resultJson) {
        try {
            Map<String, Object> actual = objectMapper.readValue(resultJson, Map.class);
            accuracyService.evaluateReplayIfApplicable(task, actual);
        } catch (Exception ex) {
            // 评测记录失败不应回滚已持久化的识别结果；下次重跑仍可再次产生评测。
            log.warn("预案任务完成后生成准确率评测失败，taskId={}", task.taskId(), ex);
        }
    }
    private PlanDigitizeResponse digitizeUpload(
            PlanDigitizeTask task, ProcessingProgressListener progressListener) {
        DownloadedDocument document = new DownloadedDocument(
                Path.of(task.sourcePath()),
                task.fileName(),
                task.contentType(),
                task.fileSize() == null ? 0 : task.fileSize(),
                DocumentFileType.valueOf(task.fileType()));
        return digitizeService.digitizeDocument(document, progressListener);
    }

    private void fail(
    /**
     * 写入失败状态；若租约已丢失则只记录日志，不覆盖当前合法执行者的状态。
     */
            PlanDigitizeTask task,
            String workerId,
            String code,
            String message,
            Exception ex,
            long started) {
        int updated = repository.fail(
                task.taskId(), workerId, task.claimToken(), code, message, OffsetDateTime.now());
        if (updated == 0) {
            log.warn("任务失败时领取令牌已失效，失败状态未写入，taskId={}", task.taskId(), ex);
            return;
        }
        log.error(
                "预案数字化任务失败，taskId={}, planId={}, durationMs={}",
                task.taskId(), task.planId(), elapsedMillis(started), ex);
    }

    private static final class LeaseLostException extends RuntimeException {
    /** 用内部异常快速中断已经失去领取租约的处理链。 */
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
