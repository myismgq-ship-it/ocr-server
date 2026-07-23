package com.gsafety.ocrtool.management;

import java.util.UUID;

/** 从任务中心选择历史任务进行规则调试的请求。 */
public record PlanRuleDebugTaskRequest(String planId, UUID taskId) {
}
