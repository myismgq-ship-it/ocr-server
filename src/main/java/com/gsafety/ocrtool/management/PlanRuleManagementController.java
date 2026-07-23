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

    public PlanRuleManagementController(
            PlanRuleRevisionService service,
            PlanDigitizeService digitizeService) {
        this.service = service;
        this.digitizeService = digitizeService;
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
