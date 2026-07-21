package com.gsafety.ocrtool.document;

public interface DocumentParser {

    boolean supports(DocumentFileType fileType);

    ParsedDocument parse(DownloadedDocument document);
}
