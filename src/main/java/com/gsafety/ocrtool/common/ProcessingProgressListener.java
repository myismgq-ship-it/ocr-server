package com.gsafety.ocrtool.common;

/**
 * 处理阶段进度回调；同步接口可使用 {@link #NOOP}，异步任务用它持久化进度。
 */
@FunctionalInterface
public interface ProcessingProgressListener {

    ProcessingProgressListener NOOP = (stage, progressPercent) -> { };
    /** 不执行任何操作的默认监听器。 */

    void onProgress(ProcessingStage stage, int progressPercent);
    /**
     * 上报当前阶段和总体进度。
     *
     * @param stage 当前处理阶段
     * @param progressPercent 0 到 100 的总体进度百分比
     */
}
