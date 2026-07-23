package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.GatewayAuditFilter;
import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
/**
 * 预案规则版本管理接口。
 *
 * <p>测试接口只使用指定修订解析样本文档，不发布规则、不污染线上缓存。</p>
 */
@RequestMapping("/api/admin/plan/rules")
public class PlanRuleManagementController {

    /** 规则修订生命周期和快照编译服务。 */
    private final PlanRuleRevisionService service;
    /** 使用隔离规则快照解析样本文档。 */
    private final PlanDigitizeService digitizeService;
    /** 输出规则候选、命中和回退原因的隔离调试服务。 */
    private final PlanRuleDebugService debugService;

    public PlanRuleManagementController(
            PlanRuleRevisionService service,
            PlanDigitizeService digitizeService,
            PlanRuleDebugService debugService) {
        this.service = service;
        this.digitizeService = digitizeService;
        this.debugService = debugService;
    }

    /** 创建规则草稿。 */
    @PostMapping("/drafts")
    public ResponseEntity<PlanRuleRevisionResponse> createDraft(
            @RequestBody List<PlanRuleDefinition> rules,
            @RequestHeader(value = GatewayAuditFilter.CALLER_ID_HEADER, required = false) String callerId) {
        return ResponseEntity.accepted().body(service.createDraft(rules, callerId));
    }

    /** 查询规则版本历史。 */
    @GetMapping("/revisions")
    public List<PlanRuleRevisionResponse> history() {
        return service.history();
    }
    /** Queries the active database rule rows used by the parser. */
    @GetMapping("/active")
    public PlanActiveRuleResponse activeRules() {
        return debugService.activeRules();
    }

    /** 校验规则草稿。 */
    @PostMapping("/revisions/{revisionId}/validate")
    public ValidationResponse validate(@PathVariable UUID revisionId) {
        return service.validate(revisionId);
    }

    /**
     * 使用指定规则修订测试上传 Word/PDF，不改变线上状态。
     */
    @PostMapping("/revisions/{revisionId}/test")
    public PlanDigitizeResponse test(
            @PathVariable UUID revisionId,
            @RequestPart("file") MultipartFile file) {
        return digitizeService.digitize(file, service.snapshot(revisionId));
    }

    /**
     * 上传临时样本文档，返回规则候选、命中、回退和淘汰原因；不发布或保存规则。
     */
    @PostMapping("/revisions/{revisionId}/debug/file")
    public PlanRuleDebugResponse debugFile(
            @PathVariable UUID revisionId,
            @RequestPart("file") MultipartFile file) {
        return debugService.debugFile(revisionId, file);
    }

    /** 使用历史终态任务的来源文档重新调试指定规则版本。 */
    @PostMapping("/revisions/{revisionId}/debug/task")
    public PlanRuleDebugResponse debugTask(
            @PathVariable UUID revisionId,
            @RequestBody PlanRuleDebugTaskRequest request) {
        return debugService.debugTask(revisionId, request);
    }
    /** Debugs a temporary upload with the current database-active rules. */
    @PostMapping("/debug/file")
    public PlanRuleDebugResponse debugActiveFile(@RequestPart("file") MultipartFile file) {
        return debugService.debugActiveFile(file);
    }

    /** Debugs a terminal task with the current database-active rules. */
    @PostMapping("/debug/task")
    public PlanRuleDebugResponse debugActiveTask(@RequestBody PlanRuleDebugTaskRequest request) {
        return debugService.debugActiveTask(request);
    }
    /** 发布规则草稿。 */
    @PostMapping("/revisions/{revisionId}/publish")
    public PlanRuleRevisionResponse publish(@PathVariable UUID revisionId) {
        return service.publish(revisionId);
    }

    /** 将历史修订复制为新版本并发布。 */
    @PostMapping("/revisions/{revisionId}/rollback")
    public PlanRuleRevisionResponse rollback(
            @PathVariable UUID revisionId,
            @RequestHeader(value = GatewayAuditFilter.CALLER_ID_HEADER, required = false) String callerId) {
        return service.rollback(revisionId, callerId);
    }
}
