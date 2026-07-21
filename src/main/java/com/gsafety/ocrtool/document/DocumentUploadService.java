package com.gsafety.ocrtool.document;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {

    private final PlanProperties properties;
    private final DocumentDownloadService documentDownloadService;

    public DocumentUploadService(PlanProperties properties, DocumentDownloadService documentDownloadService) {
        this.properties = properties;
        this.documentDownloadService = documentDownloadService;
    }

    public DownloadedDocument upload(MultipartFile file) {
        validate(file);
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("plan-upload-", ".bin");
            file.transferTo(tempFile);
            String fileName = StringUtils.hasText(file.getOriginalFilename())
                    ? sanitizeFileName(file.getOriginalFilename())
                    : "document";
            DocumentFileType fileType = documentDownloadService.detectFileType(
                    tempFile,
                    fileName,
                    file.getContentType());
            return new DownloadedDocument(tempFile, fileName, file.getContentType(), file.getSize(), fileType);
        } catch (OcrException ex) {
            deleteQuietly(tempFile);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(tempFile);
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_PARSE_FAILED", "上传文档处理失败。", ex);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "上传文档不能为空。");
        }
        if (file.getSize() > properties.getDocument().getMaxSize().toBytes()) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "DOCUMENT_TOO_LARGE", "文档大小超过限制。");
        }
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 上传文档处理失败时尽力清理临时文件。
        }
    }
}
