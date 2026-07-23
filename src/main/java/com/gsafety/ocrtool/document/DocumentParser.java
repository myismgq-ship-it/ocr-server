package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.ProcessingProgressListener;

/**
 * 按真实文档类型选择的解析器扩展点。
 */
public interface DocumentParser {

    boolean supports(DocumentFileType fileType);

    /** 判断当前解析器是否支持该文件类型。 */
    ParsedDocument parse(DownloadedDocument document);

    /** 不需要细粒度进度时解析文档。 */
    default ParsedDocument parse(DownloadedDocument document, ProcessingProgressListener progressListener) {
        return parse(document);
    /**
     * 解析文档并可选上报进度；默认实现保持旧解析器兼容。
     */
    }
}
