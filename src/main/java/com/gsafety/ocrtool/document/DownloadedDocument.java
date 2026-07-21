package com.gsafety.ocrtool.document;

import java.nio.file.Files;
import java.nio.file.Path;

public record DownloadedDocument(
        Path path,
        String fileName,
        String contentType,
        long size,
        DocumentFileType fileType) implements AutoCloseable {

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 下载文件是请求内临时文件，清理失败不影响解析结果返回。
        }
    }
}
