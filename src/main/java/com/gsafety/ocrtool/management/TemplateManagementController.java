package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.GatewayAuditFilter;
import com.gsafety.ocrtool.extraction.OcrExtractResult;
import com.gsafety.ocrtool.extraction.OcrExtractService;
import java.util.List;
import java.util.Map;
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
 * OCR 模板版本管理接口。
 *
 * <p>这些接口应仅由网关授权的管理端调用；测试接口使用指定修订，不影响线上已发布模板。</p>
 */
@RequestMapping("/api/admin/ocr/templates")
public class TemplateManagementController {

    /** 模板修订生命周期服务。 */
    private final TemplateRevisionService revisionService;
    /** 使用指定模板对上传样本执行抽取。 */
    private final OcrExtractService extractService;

    public TemplateManagementController(
            TemplateRevisionService revisionService,
            OcrExtractService extractService) {
        this.revisionService = revisionService;
        this.extractService = extractService;
    }

    /** 创建模板草稿并记录网关调用方。 */
    @PostMapping("/{templateCode}/drafts")
    public ResponseEntity<TemplateRevisionResponse> createDraft(
            @PathVariable String templateCode,
            @RequestBody Map<String, Object> definition,
            @RequestHeader(value = GatewayAuditFilter.CALLER_ID_HEADER, required = false) String callerId) {
        return ResponseEntity.accepted()
                .body(revisionService.createDraft(templateCode, definition, callerId));
    }

    /** 查询指定模板的版本历史。 */
    @GetMapping("/{templateCode}/revisions")
    public List<TemplateRevisionResponse> history(@PathVariable String templateCode) {
        return revisionService.history(templateCode);
    }

    /** 校验草稿结构和字段规则。 */
    @PostMapping("/revisions/{revisionId}/validate")
    public ValidationResponse validate(@PathVariable UUID revisionId) {
        return revisionService.validate(revisionId);
    }

    /** 发布有效草稿。 */
    @PostMapping("/revisions/{revisionId}/publish")
    public TemplateRevisionResponse publish(@PathVariable UUID revisionId) {
        return revisionService.publish(revisionId);
    }

    /** 将历史修订复制为新版本并发布。 */
    @PostMapping("/revisions/{revisionId}/rollback")
    public TemplateRevisionResponse rollback(
            @PathVariable UUID revisionId,
            @RequestHeader(value = GatewayAuditFilter.CALLER_ID_HEADER, required = false) String callerId) {
        return revisionService.rollback(revisionId, callerId);
    }

    /**
     * 使用指定修订测试上传图片，不改变修订状态。
     */
    @PostMapping("/revisions/{revisionId}/test")
    public OcrExtractResult test(
            @PathVariable UUID revisionId,
            @RequestPart("file") MultipartFile file) {
        TemplateRevisionResponse revision = revisionService.get(revisionId);
        return extractService.extractWithTemplate(
                revision.templateCode(),
                revisionService.template(revisionId),
                file);
    }
}
