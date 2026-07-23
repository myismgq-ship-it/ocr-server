package com.gsafety.ocrtool.management;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
/**
 * 预案版本差异查询和 Excel 导出接口。
 */
@RequestMapping("/api/plans/{planId}/digitize/compare")
public class PlanComparisonController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PlanComparisonService service;

    public PlanComparisonController(PlanComparisonService service) {
        this.service = service;
    }

    /** 返回两次任务结果的结构化 JSON 差异。 */
    @GetMapping
    public PlanComparisonResponse compare(
            @PathVariable String planId,
            @RequestParam UUID fromTaskId,
            @RequestParam UUID toTaskId) {
        return service.compare(planId, fromTaskId, toTaskId);
    }

    /** 导出与 JSON 比较结果一致的 XLSX 差异表。 */
    @GetMapping(value = "/export", produces =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(
            @PathVariable String planId,
            @RequestParam UUID fromTaskId,
            @RequestParam UUID toTaskId) {
        PlanComparisonResponse comparison = service.compare(planId, fromTaskId, toTaskId);
        byte[] body = service.exportExcel(comparison);
        String fileName = "plan-diff-" + fromTaskId + "-" + toTaskId + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(body);
    }
}
