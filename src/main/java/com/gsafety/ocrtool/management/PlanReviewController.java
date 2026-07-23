package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.GatewayAuditFilter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
/**
 * 预案任务人工复核接口。
 *
 * <p>前端可提交完整修订结果并按任务查看审计历史；调用方由网关审计头记录。</p>
 */
@RequestMapping("/api/plans/{planId}/digitize/tasks/{taskId}/reviews")
public class PlanReviewController {

    private final PlanReviewService service;

    public PlanReviewController(PlanReviewService service) {
        this.service = service;
    }

    /** 提交一次新的人工复核修订。 */
    @PostMapping
    public ResponseEntity<ReviewResponse> submit(
            @PathVariable String planId,
            @PathVariable UUID taskId,
            @Valid @RequestBody ReviewRequest request,
            @RequestHeader(value = GatewayAuditFilter.CALLER_ID_HEADER, required = false) String callerId) {
        return ResponseEntity.accepted().body(service.submit(planId, taskId, request, callerId));
    }

    /** 查询指定任务的复核历史。 */
    @GetMapping
    public List<ReviewResponse> history(
            @PathVariable String planId,
            @PathVariable UUID taskId) {
        return service.history(planId, taskId);
    }
}
