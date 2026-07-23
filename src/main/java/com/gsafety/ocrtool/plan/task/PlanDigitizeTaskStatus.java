package com.gsafety.ocrtool.plan.task;

/** 异步任务持久化状态及中文展示名称。 */
public enum PlanDigitizeTaskStatus {
    /** 尚未被执行者领取。 */
    QUEUED("排队中"),
    /** 已绑定 workerId 和 claimToken。 */
    RUNNING("执行中"),
    /** 结果已成功提交。 */
    COMPLETED("已完成"),
    /** 当前领取尝试以失败结束。 */
    FAILED("执行失败"),
    /** 调用方取消，原领取令牌已失效。 */
    CANCELLED("已取消");

    /** 对外展示的稳定中文名称。 */
    private final String displayName;

    PlanDigitizeTaskStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
