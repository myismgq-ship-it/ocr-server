package com.gsafety.ocrtool.document;

import java.util.List;

public record ParsedDocument(
        String fileName,
        DocumentFileType fileType,
        DocumentParseMode parseMode,
        List<DocumentBlock> blocks,
        List<String> warnings) {
}
