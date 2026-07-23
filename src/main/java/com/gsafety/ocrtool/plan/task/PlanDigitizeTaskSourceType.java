package com.gsafety.ocrtool.plan.task;

/** 异步任务原始文档来源。 */
public enum PlanDigitizeTaskSourceType {
    /** multipart 上传并保存到任务共享目录。 */
    UPLOAD,
    /** 执行时从受控 HTTP/HTTPS 地址下载。 */
    URL
}
