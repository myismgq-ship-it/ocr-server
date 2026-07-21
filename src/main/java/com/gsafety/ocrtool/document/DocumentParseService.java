package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DocumentParseService {

    private final List<DocumentParser> parsers;

    public DocumentParseService(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public ParsedDocument parse(DownloadedDocument document) {
        return parsers.stream()
                .filter(parser -> parser.supports(document.fileType()))
                .findFirst()
                .orElseThrow(() -> new OcrException(
                        HttpStatus.BAD_REQUEST,
                        "UNSUPPORTED_DOCUMENT_TYPE",
                        "不支持的文档类型。"))
                .parse(document);
    }
}
