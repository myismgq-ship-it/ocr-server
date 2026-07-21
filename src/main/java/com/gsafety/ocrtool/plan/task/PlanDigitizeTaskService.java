package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import com.gsafety.ocrtool.response.PlanDigitizeTaskPageResponse;
import com.gsafety.ocrtool.response.PlanDigitizeTaskResponse;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanDigitizeTaskService {

    private final PlanDigitizeTaskRepository repository;
    private final PlanTaskStorageService storageService;
    private final ObjectMapper objectMapper;

    public PlanDigitizeTaskService(
            PlanDigitizeTaskRepository repository,
            PlanTaskStorageService storageService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    public PlanDigitizeTaskResponse createUpload(String planId, MultipartFile file) {
        validatePlanId(planId);
        var active = repository.findActive(planId);
        if (active.isPresent()) {
            return toResponse(active.get(), true);
        }
        UUID taskId = UUID.randomUUID();
        StoredTaskFile stored = storageService.store(taskId, file);
        PlanDigitizeTask task = newTask(
                taskId, planId, PlanDigitizeTaskSourceType.UPLOAD, stored, null, null);
        try {
            repository.insert(task);
            return toResponse(task, false);
        } catch (DuplicateKeyException ex) {
            storageService.delete(stored.path());
            return toResponse(repository.findActive(planId).orElseThrow(() -> ex), true);
        } catch (RuntimeException ex) {
            storageService.delete(stored.path());
            throw ex;
        }
    }

    public PlanDigitizeTaskResponse createUrl(String planId, String documentUrl) {
        validatePlanId(planId);
        validateDocumentUrl(documentUrl);
        var active = repository.findActive(planId);
        if (active.isPresent()) {
            return toResponse(active.get(), true);
        }
        UUID taskId = UUID.randomUUID();
        PlanDigitizeTask task = newTask(
                taskId, planId, PlanDigitizeTaskSourceType.URL, null, documentUrl.trim(), null);
        try {
            repository.insert(task);
            return toResponse(task, false);
        } catch (DuplicateKeyException ex) {
            return toResponse(repository.findActive(planId).orElseThrow(() -> ex), true);
        }
    }

    public PlanDigitizeTaskResponse get(String planId, UUID taskId) {
        validatePlanId(planId);
        return toResponse(find(planId, taskId), false);
    }

    public PlanDigitizeTaskResponse latest(String planId) {
        validatePlanId(planId);
        return toResponse(repository.findLatest(planId).orElseThrow(() -> notFound("未找到预案数字化任务。")), false);
    }

    public PlanDigitizeTaskPageResponse history(String planId, int page, int size) {
        validatePlanId(planId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long total = repository.countHistory(planId);
        List<PlanDigitizeTaskResponse> items = repository
                .findHistory(planId, safeSize, safePage * safeSize)
                .stream()
                .map(task -> toResponse(task, false))
                .toList();
        int totalPages = total == 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
        return new PlanDigitizeTaskPageResponse(items, safePage, safeSize, total, totalPages);
    }

    public PlanDigitizeTaskResponse retry(String planId, UUID failedTaskId) {
        validatePlanId(planId);
        PlanDigitizeTask failed = find(planId, failedTaskId);
        if (failed.status() != PlanDigitizeTaskStatus.FAILED) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TASK_NOT_RETRYABLE", "只有执行失败的任务可以重试。");
        }
        var active = repository.findActive(planId);
        if (active.isPresent()) {
            return toResponse(active.get(), true);
        }
        UUID taskId = UUID.randomUUID();
        StoredTaskFile stored = failed.sourceType() == PlanDigitizeTaskSourceType.UPLOAD
                ? storageService.copy(taskId, failed)
                : null;
        PlanDigitizeTask task = newTask(
                taskId, planId, failed.sourceType(), stored, failed.sourceUrl(), failed.taskId());
        try {
            repository.insert(task);
            return toResponse(task, false);
        } catch (DuplicateKeyException ex) {
            if (stored != null) {
                storageService.delete(stored.path());
            }
            return toResponse(repository.findActive(planId).orElseThrow(() -> ex), true);
        } catch (RuntimeException ex) {
            if (stored != null) {
                storageService.delete(stored.path());
            }
            throw ex;
        }
    }

    private PlanDigitizeTask newTask(
            UUID taskId,
            String planId,
            PlanDigitizeTaskSourceType sourceType,
            StoredTaskFile file,
            String sourceUrl,
            UUID retryOf) {
        OffsetDateTime now = OffsetDateTime.now();
        return new PlanDigitizeTask(
                taskId,
                planId,
                sourceType,
                file == null ? null : file.fileType(),
                file == null ? fileNameFromUrl(sourceUrl) : file.fileName(),
                file == null ? null : file.contentType(),
                file == null ? null : file.size(),
                sourceUrl,
                file == null ? null : file.path(),
                PlanDigitizeTaskStatus.QUEUED,
                null,
                null,
                null,
                null,
                null,
                retryOf,
                now,
                null,
                null,
                now,
                now);
    }

    private PlanDigitizeTask find(String planId, UUID taskId) {
        return repository.findByPlanAndTaskId(planId, taskId)
                .orElseThrow(() -> notFound("未找到对应的预案数字化任务。"));
    }

    private PlanDigitizeTaskResponse toResponse(PlanDigitizeTask task, boolean reused) {
        return new PlanDigitizeTaskResponse(
                task.taskId(),
                task.planId(),
                task.status().name(),
                task.status().displayName(),
                task.fileName(),
                task.queuedAt(),
                task.startedAt(),
                task.completedAt(),
                task.errorCode(),
                task.errorMessage(),
                reused,
                readResult(task.resultJson()));
    }

    private PlanDigitizeResponse readResult(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlanDigitizeResponse.class);
        } catch (Exception ex) {
            throw new OcrException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TASK_RESULT_INVALID",
                    "任务结果读取失败。",
                    ex);
        }
    }

    private void validatePlanId(String planId) {
        if (!StringUtils.hasText(planId) || planId.trim().length() > 64) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_PLAN_ID", "预案 ID 不能为空且不能超过 64 个字符。");
        }
    }

    private void validateDocumentUrl(String documentUrl) {
        try {
            URI uri = URI.create(documentUrl == null ? "" : documentUrl.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_URL", "文档 URL 只支持 http/https。");
        }
    }

    private String fileNameFromUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String path = URI.create(url).getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : "document";
    }

    private OcrException notFound(String message) {
        return new OcrException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", message);
    }
}
