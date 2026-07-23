package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.common.ProcessingProgressListener;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 根据文件真实类型路由到对应 Word/PDF 解析器。
 */
@Service
public class DocumentParseService {

    private final List<DocumentParser> parsers;
    /** Spring 注入的全部文档解析器实现。 */

    public DocumentParseService(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public ParsedDocument parse(DownloadedDocument document) {
    /** 使用空进度监听器解析文档。 */
        return parse(document, ProcessingProgressListener.NOOP);
    }

    public ParsedDocument parse(DownloadedDocument document, ProcessingProgressListener progressListener) {
    /**
     * 选择首个支持当前类型的解析器并传递任务进度监听器。
     */
        return parsers.stream()
                .filter(parser -> parser.supports(document.fileType()))
                .findFirst()
                .orElseThrow(() -> new OcrException(
                        HttpStatus.BAD_REQUEST,
                        "UNSUPPORTED_DOCUMENT_TYPE",
                        "不支持的文档类型。"))
                .parse(document, progressListener == null ? ProcessingProgressListener.NOOP : progressListener);
    }
}
