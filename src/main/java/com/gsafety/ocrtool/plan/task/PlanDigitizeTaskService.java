package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.management.PlanAccuracyService;
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

/**
 * 异步任务中心的应用服务。
 *
 * <p>负责创建去重、分页查询、失败重试、取消和向后兼容的响应映射，不直接执行 OCR。</p>
 */
@Service
public class PlanDigitizeTaskService {

    /** 任务元数据和状态持久化。 */
    private final PlanDigitizeTaskRepository repository;
    /** 上传任务源文件持久化和安全清理。 */
    private final PlanTaskStorageService storageService;
    /** 完成结果 JSON 与响应对象之间的转换器。 */
    private final ObjectMapper objectMapper;
    /** 用于校验历史重跑是否已有人工确认样本。*/
    private final PlanAccuracyService accuracyService;

    public PlanDigitizeTaskService(
            PlanDigitizeTaskRepository repository,
            PlanTaskStorageService storageService,
            ObjectMapper objectMapper,
            PlanAccuracyService accuracyService) {
        this.repository = repository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.accuracyService = accuracyService;
    }

    /**
     * 上传文档并创建任务；同一预案已有活动任务时直接复用。
     *
     * @return 新任务或当前活动任务，响应中的 reused 标识是否复用
     */
    public PlanDigitizeTaskResponse createUpload(String planId, MultipartFile file) {
        validatePlanId(planId);
        // 先做快速查询减少无谓文件落盘，数据库唯一索引仍是最终并发保护。
        var active = repository.findActive(planId);
        if (active.isPresent()) {
            return toResponse(active.get(), true);
        }
        UUID taskId = UUID.randomUUID();
        StoredTaskFile stored = storageService.store(taskId, file);
        // 文件必须先成功持久化才能入库；后续任何入库失败都要删除本次文件。
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

    /**
     * 为远程文档 URL 创建异步任务，同一预案活动任务保持唯一。
     */
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

    /** 查询指定任务详情和结果。 */
    public PlanDigitizeTaskResponse get(String planId, UUID taskId) {
        validatePlanId(planId);
        return toResponse(find(planId, taskId), false);
    }

    /** 查询预案最新一次任务。 */
    public PlanDigitizeTaskResponse latest(String planId) {
        validatePlanId(planId);
        return toResponse(repository.findLatest(planId).orElseThrow(() -> notFound("未找到预案数字化任务。")), false);
    }

    /**
     * 分页查询任务历史，页码最小为 0、每页最大为 100。
     */
    public PlanDigitizeTaskPageResponse history(String planId, int page, int size) {
        validatePlanId(planId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long offset = (long) safePage * safeSize;
        if (offset > Integer.MAX_VALUE) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页位置超过允许范围。");
        }
        long total = repository.countHistory(planId);
        List<PlanDigitizeTaskResponse> items = repository
                .findHistory(planId, safeSize, (int) offset)
                .stream()
                .map(task -> toResponse(task, false))
                .toList();
        int totalPages = total == 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
        return new PlanDigitizeTaskPageResponse(items, safePage, safeSize, total, totalPages);
    }

    /**
     * 从失败任务创建一次新的重试任务。
     *
     * <p>上传任务复制保留的源文件；URL 任务复用原地址，但始终生成新的任务 ID 和领取尝试。</p>
     */
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

    /**
     * 取消排队中或运行中的任务，并尽力清理上传源文件。
     */
    /**
     * 使用当前已发布规则重新执行已人工复核的历史任务。
     *
     * <p>上传文件必须仍在受控任务存储中；URL 任务会再次经过下载白名单与 SSRF 校验。
     * 重跑创建新任务，不会覆盖源任务、人工样本或既有评测记录。</p>
     */
    public PlanDigitizeTaskResponse replayForEvaluation(String planId, UUID sourceTaskId) {
        validatePlanId(planId);
        PlanDigitizeTask source = find(planId, sourceTaskId);
        if (source.status() != PlanDigitizeTaskStatus.COMPLETED) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TASK_NOT_REPLAYABLE", "只有已完成任务可以重新评测。");
        }
        if (!accuracyService.hasActiveSample(sourceTaskId)) {
            throw new OcrException(HttpStatus.CONFLICT, "TASK_NO_ACCURACY_SAMPLE", "请先完成人工复核，系统才能将其作为准确率样本重新评测。");
        }
        var active = repository.findActive(planId);
        if (active.isPresent()) {
            return toResponse(active.get(), true);
        }
        UUID taskId = UUID.randomUUID();
        StoredTaskFile stored = source.sourceType() == PlanDigitizeTaskSourceType.UPLOAD
                ? storageService.copy(taskId, source)
                : null;
        PlanDigitizeTask replay = newTask(
                taskId, planId, source.sourceType(), stored, source.sourceUrl(), source.taskId());
        try {
            repository.insert(replay);
            return toResponse(replay, false);
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
    public PlanDigitizeTaskResponse cancel(String planId, UUID taskId) {
        validatePlanId(planId);
        PlanDigitizeTask task = find(planId, taskId);
        if (task.status() == PlanDigitizeTaskStatus.COMPLETED
                || task.status() == PlanDigitizeTaskStatus.FAILED
                || task.status() == PlanDigitizeTaskStatus.CANCELLED) {
            return toResponse(task, false);
        }
        // 数据库取消会清空 claimToken，正在运行的旧执行者后续更新将自然失败。
        if (repository.cancel(planId, taskId, OffsetDateTime.now()) == 0) {
            return toResponse(find(planId, taskId), false);
        }
        if (task.sourceType() == PlanDigitizeTaskSourceType.UPLOAD
                && storageService.delete(task.sourcePath())) {
            repository.clearSourcePath(taskId);
        }
        return toResponse(find(planId, taskId), false);
    }

    /**
     * 构造初始 QUEUED 任务快照，所有运行时字段从零值开始。
     */
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
                null,
                "QUEUED",
                0,
                0,
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
                readResult(task.resultJson()),
                task.stage(),
                task.progressPercent(),
                task.attempt(),
                task.updatedAt(),
                task.ruleVersion());
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
