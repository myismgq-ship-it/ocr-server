package com.gsafety.ocrtool.management;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 预案工作台目录接口。 */
@RestController
@RequestMapping("/api/plans")
public class PlanCatalogController {

    /** 预案目录业务服务。 */
    private final PlanCatalogService service;

    public PlanCatalogController(PlanCatalogService service) {
        this.service = service;
    }

    /** 查询工作台可选择的全部真实预案。 */
    @GetMapping
    public List<PlanCatalogResponse> list() {
        return service.list();
    }

    /** 新增预案目录。 */
    @PostMapping
    public ResponseEntity<PlanCatalogResponse> create(@Valid @RequestBody PlanCatalogRequest request) {
        PlanCatalogResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/plans/" + created.id())).body(created);
    }

    /** 修改或登记指定预案的展示元数据。 */
    @PutMapping("/{planId}")
    public PlanCatalogResponse save(
            @PathVariable String planId,
            @Valid @RequestBody PlanCatalogRequest request) {
        return service.save(planId, request);
    }
}
