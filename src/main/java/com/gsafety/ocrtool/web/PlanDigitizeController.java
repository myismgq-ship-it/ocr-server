package com.gsafety.ocrtool.web;

import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskService;
import com.gsafety.ocrtool.request.PlanDigitizeRequest;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import com.gsafety.ocrtool.response.PlanDigitizeTaskPageResponse;
import com.gsafety.ocrtool.response.PlanDigitizeTaskResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
/**
 * 预案同步解析和异步任务中心 HTTP 入口。
 *
 * <p>保留原同步路径，同时提供向后兼容的创建、详情、历史、重试和取消接口。</p>
 */
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/plans")
public class PlanDigitizeController {

    private final PlanDigitizeService planDigitizeService;
    private final PlanDigitizeTaskService taskService;

    public PlanDigitizeController(PlanDigitizeService planDigitizeService) {
        this(planDigitizeService, null);
    }

    @Autowired
    public PlanDigitizeController(
            PlanDigitizeService planDigitizeService,
            PlanDigitizeTaskService taskService) {
        this.planDigitizeService = planDigitizeService;
        this.taskService = taskService;
    }

    /**
     * 预案数字化同步解析接口。
     *
     * <p>调用方传入已存储文档的 HTTP/HTTPS URL，服务端下载 Word/PDF 文档，
     * 抽取“指挥体系”和各响应等级内容，并返回结构化 JSON。接口不保存原文件和解析结果。</p>
     *
     * @param request 文档 URL 请求体
     * @return 预案关键内容结构化结果
     */
    @PostMapping("/digitize")
    public PlanDigitizeResponse digitize(@Valid @RequestBody PlanDigitizeRequest request) {
        return planDigitizeService.digitize(request.documentUrl());
    }

    /**
     * 预案数字化手动上传解析接口。
     *
     * <p>调用方以 multipart/form-data 上传 Word/PDF 文件，字段名固定为 {@code file}。
     * 解析流程和 URL 接口一致，接口不保存原文件和解析结果。</p>
     *
     * @param file 待解析预案文档
     * @return 预案关键内容结构化结果
     */
    @PostMapping("/digitize/upload")
    public PlanDigitizeResponse digitizeUpload(@RequestPart("file") MultipartFile file) {
        return planDigitizeService.digitize(file);
    }

    /** 上传文档并创建异步任务。 */
    @PostMapping("/{planId}/digitize/tasks/upload")
    public ResponseEntity<PlanDigitizeTaskResponse> createUploadTask(
            @PathVariable("planId") String planId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.accepted().body(taskService.createUpload(planId, file));
    }

    /** 使用远程文档 URL 创建异步任务。 */
    @PostMapping("/{planId}/digitize/tasks")
    public ResponseEntity<PlanDigitizeTaskResponse> createUrlTask(
            @PathVariable("planId") String planId,
            @Valid @RequestBody PlanDigitizeRequest request) {
        return ResponseEntity.accepted().body(taskService.createUrl(planId, request.documentUrl()));
    }

    /** 查询预案最新一次任务。 */
    @GetMapping("/{planId}/digitize/tasks/latest")
    public PlanDigitizeTaskResponse latestTask(@PathVariable("planId") String planId) {
        return taskService.latest(planId);
    }

    /** 按 taskId 查询任务详情。 */
    @GetMapping("/{planId}/digitize/tasks/{taskId}")
    public PlanDigitizeTaskResponse getTask(
            @PathVariable("planId") String planId,
            @PathVariable("taskId") UUID taskId) {
        return taskService.get(planId, taskId);
    }

    /** 分页查询预案任务历史。 */
    @GetMapping("/{planId}/digitize/tasks")
    public PlanDigitizeTaskPageResponse taskHistory(
            @PathVariable("planId") String planId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return taskService.history(planId, page, size);
    }

    /** 为失败任务创建新的重试任务。 */
    @PostMapping("/{planId}/digitize/tasks/{taskId}/retry")
    public ResponseEntity<PlanDigitizeTaskResponse> retryTask(
            @PathVariable("planId") String planId,
            @PathVariable("taskId") UUID taskId) {
        return ResponseEntity.accepted().body(taskService.retry(planId, taskId));
    }

    /** 使用最新规则重跑已人工复核任务，并在完成后生成结构覆盖率评测。 */
    @PostMapping("/{planId}/digitize/tasks/{taskId}/replay")
    public ResponseEntity<PlanDigitizeTaskResponse> replayTask(
            @PathVariable("planId") String planId,
            @PathVariable("taskId") UUID taskId) {
        return ResponseEntity.accepted().body(taskService.replayForEvaluation(planId, taskId));
    }

    /** 取消排队中或运行中的任务。 */
    @PostMapping("/{planId}/digitize/tasks/{taskId}/cancel")
    public PlanDigitizeTaskResponse cancelTask(
            @PathVariable("planId") String planId,
            @PathVariable("taskId") UUID taskId) {
        return taskService.cancel(planId, taskId);
    }
}
