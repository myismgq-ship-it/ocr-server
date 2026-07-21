package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.document.DownloadedDocument;
import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanDigitizeTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(PlanDigitizeTaskWorker.class);

    private final PlanDigitizeService digitizeService;
    private final PlanDigitizeTaskRepository repository;
    private final PlanTaskStorageService storageService;
    private final ObjectMapper objectMapper;

    public PlanDigitizeTaskWorker(
            PlanDigitizeService digitizeService,
            PlanDigitizeTaskRepository repository,
            PlanTaskStorageService storageService,
            ObjectMapper objectMapper) {
        this.digitizeService = digitizeService;
        this.repository = repository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    public void execute(PlanDigitizeTask task, String workerId) {
        long started = System.nanoTime();
        try {
            PlanDigitizeResponse result = task.sourceType() == PlanDigitizeTaskSourceType.URL
                    ? digitizeService.digitize(task.sourceUrl())
                    : digitizeUpload(task);
            String json = objectMapper.writeValueAsString(result);
            int updated = repository.complete(task.taskId(), workerId, json, OffsetDateTime.now());
            if (updated > 0 && task.sourceType() == PlanDigitizeTaskSourceType.UPLOAD) {
                if (storageService.delete(task.sourcePath())) {
                    repository.clearSourcePath(task.taskId());
                }
            }
            log.info(
                    "预案数字化任务完成，taskId={}, planId={}, durationMs={}",
                    task.taskId(), task.planId(), elapsedMillis(started));
        } catch (OcrException ex) {
            fail(task, workerId, ex.getCode(), ex.getMessage(), ex, started);
        } catch (Exception ex) {
            fail(task, workerId, "TASK_EXECUTION_FAILED", "预案数字化任务执行失败。", ex, started);
        }
    }

    private PlanDigitizeResponse digitizeUpload(PlanDigitizeTask task) {
        DownloadedDocument document = new DownloadedDocument(
                Path.of(task.sourcePath()),
                task.fileName(),
                task.contentType(),
                task.fileSize() == null ? 0 : task.fileSize(),
                DocumentFileType.valueOf(task.fileType()));
        return digitizeService.digitizeDocument(document);
    }

    private void fail(
            PlanDigitizeTask task,
            String workerId,
            String code,
            String message,
            Exception ex,
            long started) {
        repository.fail(task.taskId(), workerId, code, message, OffsetDateTime.now());
        log.error(
                "预案数字化任务失败，taskId={}, planId={}, durationMs={}",
                task.taskId(), task.planId(), elapsedMillis(started), ex);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
