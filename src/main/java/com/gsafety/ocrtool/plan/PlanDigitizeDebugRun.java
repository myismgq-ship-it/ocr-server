package com.gsafety.ocrtool.plan;

import com.gsafety.ocrtool.document.ParsedDocument;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import com.gsafety.ocrtool.segment.SegmentResult;

/**
 * 一次隔离规则调试的中间结果。
 *
 * <p>仅在管理端显式发起调试时创建，保留解析块和分段结果供诊断服务解释候选命中过程；
 * 常规数字化任务仍只返回 {@link PlanDigitizeResponse}，不会扩大任务结果 JSON。</p>
 */
public record PlanDigitizeDebugRun(
        PlanDigitizeResponse result,
        ParsedDocument parsedDocument,
        SegmentResult segmentResult) {
}
