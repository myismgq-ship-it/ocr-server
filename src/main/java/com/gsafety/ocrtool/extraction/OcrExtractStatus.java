package com.gsafety.ocrtool.extraction;

/** 模板字段抽取的质量状态，缺失、低置信度和格式无效彼此独立。 */
public enum OcrExtractStatus {
    /** 未命中标签或没有可用字段值。 */
    MISSING,
    /** 已提取值，但置信度未达到模板阈值。 */
    LOW_CONFIDENCE,
    /** 已提取值，但没有通过字段正则校验。 */
    INVALID,
    /** 值、置信度和格式均满足模板要求。 */
    EXTRACTED
}
