package com.gsafety.ocrtool.management;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 人工样本和历史任务回归评测的查询入口。 */
@RestController
@RequestMapping("/api/plans/{planId}/digitize")
public class PlanAccuracyController {

    private final PlanAccuracyService service;

    public PlanAccuracyController(PlanAccuracyService service) {
        this.service = service;
    }

    /** 查询人工复核自动沉淀的样本历史。 */
    @GetMapping("/accuracy-samples")
    public List<PlanAccuracySampleResponse> samples(@PathVariable String planId) {
        return service.samples(planId);
    }

    /** 查询某次重跑任务的结构覆盖率评测结果。 */
    @GetMapping("/tasks/{taskId}/accuracy-evaluation")
    public PlanAccuracyEvaluationResponse evaluation(
            @PathVariable String planId,
            @PathVariable UUID taskId) {
        return service.evaluation(planId, taskId);
    }
}
