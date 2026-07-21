package com.gsafety.ocrtool.plan.task;

public enum PlanDigitizeTaskStatus {
    QUEUED("排队中"),
    RUNNING("执行中"),
    COMPLETED("已完成"),
    FAILED("执行失败");

    private final String displayName;

    PlanDigitizeTaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
