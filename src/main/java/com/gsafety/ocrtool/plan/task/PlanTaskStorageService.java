package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.document.DocumentUploadService;
import com.gsafety.ocrtool.document.DownloadedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PlanTaskStorageService {

    private final Path root;
    private final DocumentUploadService uploadService;

    public PlanTaskStorageService(PlanProperties properties, DocumentUploadService uploadService) {
        this.root = Path.of(properties.getTask().getStorageDirectory()).toAbsolutePath().normalize();
        this.uploadService = uploadService;
    }

    public StoredTaskFile store(UUID taskId, MultipartFile file) {
        try (DownloadedDocument document = uploadService.upload(file)) {
            Files.createDirectories(root);
            Path target = target(taskId);
            Files.move(document.path(), target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredTaskFile(
                    document.fileName(),
                    document.contentType(),
                    document.size(),
                    document.fileType().name(),
                    target.toString());
        } catch (OcrException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new OcrException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TASK_FILE_STORE_FAILED",
                    "异步任务文件保存失败。",
                    ex);
        }
    }

    public StoredTaskFile copy(UUID taskId, PlanDigitizeTask source) {
        if (source.sourcePath() == null || !Files.isRegularFile(Path.of(source.sourcePath()))) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TASK_SOURCE_EXPIRED", "失败任务源文件已过期，无法重试。");
        }
        try {
            Files.createDirectories(root);
            Path target = target(taskId);
            Files.copy(Path.of(source.sourcePath()), target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredTaskFile(
                    source.fileName(),
                    source.contentType(),
                    source.fileSize() == null ? Files.size(target) : source.fileSize(),
                    source.fileType(),
                    target.toString());
        } catch (IOException ex) {
            throw new OcrException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TASK_FILE_STORE_FAILED",
                    "重试任务文件准备失败。",
                    ex);
        }
    }

    public boolean delete(String sourcePath) {
        if (sourcePath == null) {
            return true;
        }
        Path path = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            return false;
        }
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Path target(UUID taskId) {
        Path target = root.resolve(taskId + ".bin").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("任务文件路径越界。");
        }
        return target;
    }
}
