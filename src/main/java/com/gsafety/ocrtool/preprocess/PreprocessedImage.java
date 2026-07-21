package com.gsafety.ocrtool.preprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record PreprocessedImage(
        Path imagePath,
        Path originalImagePath,
        String fileName,
        String contentType,
        long fileSize,
        int imageWidth,
        int imageHeight,
        List<String> steps,
        List<String> warnings,
        List<Path> tempFiles) implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PreprocessedImage.class);

    @Override
    public void close() {
        for (Path tempFile : tempFiles) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                log.warn("OCR 临时文件清理失败，path={}", tempFile, ex);
            }
        }
    }
}

