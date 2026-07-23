package com.gsafety.ocrtool.common;

import io.micrometer.core.instrument.Metrics;
import java.time.Duration;

/**
 * OCR 处理链路的轻量 Micrometer 指标入口，统一阶段名称和指标标签。
 */
public final class ProcessingMetrics {

    private ProcessingMetrics() {
    }

    /**
     * 记录从给定纳秒时间点到当前时刻的阶段耗时。
     */
    public static void record(String stage, long startedAtNanos) {
        Metrics.timer("ocr.processing.duration", "stage", stage)
                .record(Duration.ofNanos(System.nanoTime() - startedAtNanos));
    }

    /**
     * 增加一次处理事件计数，例如租约失效或任务清理。
     */
    public static void increment(String name) {
        Metrics.counter("ocr.processing.events", "event", name).increment();
    }
}
