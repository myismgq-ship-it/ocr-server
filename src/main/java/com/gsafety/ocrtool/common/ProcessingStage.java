package com.gsafety.ocrtool.common;

/** 异步预案数字化任务对外暴露的稳定处理阶段。 */
public enum ProcessingStage {
    /** 等待工作线程领取。 */
    QUEUED,
    /** 下载远程文档或准备上传文件。 */
    DOWNLOAD,
    /** 解析 Word/PDF 文档结构。 */
    PARSE,
    /** 识别扫描页或图片文字。 */
    OCR,
    /** 按业务规则切分预案章节。 */
    SEGMENT,
    /** 写入结构化结果。 */
    PERSIST,
    /** 任务成功完成。 */
    COMPLETED,
    /** 任务执行失败。 */
    FAILED,
    /** 任务被调用方取消。 */
    CANCELLED
}
