package com.gsafety.ocrtool.plan.task;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import com.gsafety.ocrtool.document.DocumentUploadService;
import com.gsafety.ocrtool.document.DownloadedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理异步上传任务的持久化源文件。
 *
 * <p>所有可删除路径都必须位于配置根目录下，避免数据库脏数据导致越界文件操作。</p>
 */
@Service
public class PlanTaskStorageService {

    private static final Logger log = LoggerFactory.getLogger(PlanTaskStorageService.class);

    /** 规范化后的任务文件根目录。 */
    private final Path root;
    private final DocumentUploadService uploadService;

    public PlanTaskStorageService(PlanProperties properties, DocumentUploadService uploadService) {
        this.root = Path.of(properties.getTask().getStorageDirectory()).toAbsolutePath().normalize();
        this.uploadService = uploadService;
    }

    /**
     * 校验上传文档并移动到任务专属路径。
     *
     * @param taskId 新任务 ID，用作稳定且不可注入的文件名
     * @return 已持久化文件的元数据
     */
    public StoredTaskFile store(UUID taskId, MultipartFile file) {
        try (DownloadedDocument document = uploadService.upload(file)) {
            Files.createDirectories(root);
            Path target = target(taskId);
            moveWithFallback(document.path(), target);
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

    /**
     * 为上传类型的失败重试复制源文件，原失败任务文件继续保留到清理周期。
     */
    public StoredTaskFile copy(UUID taskId, PlanDigitizeTask source) {
        Path sourcePath = source.sourcePath() == null
                ? null
                : Path.of(source.sourcePath()).toAbsolutePath().normalize();
        if (sourcePath == null || !sourcePath.startsWith(root) || !Files.isRegularFile(sourcePath)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TASK_SOURCE_EXPIRED", "失败任务源文件已过期，无法重试。");
        }
        try {
            Files.createDirectories(root);
            Path target = target(taskId);
            Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
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

    /**
     * 仅删除任务根目录内的文件；越界路径返回 false。
     */
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

    /**
     * 删除超过截止时间且未被任何数据库任务引用的文件。
     *
     * @param referencedPaths 数据库仍引用的源文件路径
     */
    public void cleanupOrphans(List<String> referencedPaths, OffsetDateTime cutoff) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Set<Path> referenced = new HashSet<>();
        if (referencedPaths != null) {
            referencedPaths.stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(Path::of)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(path -> path.startsWith(root))
                    .forEach(referenced::add);
        }
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .filter(path -> !referenced.contains(path.toAbsolutePath().normalize()))
                    .filter(path -> isOlderThan(path, cutoff))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            log.info("已清理孤立预案任务文件，path={}", path);
                        } catch (IOException ex) {
                            log.warn("孤立预案任务文件清理失败，path={}", path, ex);
                        }
                    });
        } catch (IOException ex) {
            log.warn("扫描孤立预案任务文件失败，root={}", root, ex);
        }
    }

    private boolean isOlderThan(Path path, OffsetDateTime cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff.toInstant());
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * 优先原子移动；跨文件系统移动失败时使用“复制到 .part 再改名”回退。
     *
     * <p>只有 staging 文件完整写入后才替换目标，避免暴露半写入文件。</p>
     */
    private void moveWithFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            // move 可能因临时目录和任务目录位于不同文件系统而失败。
        } catch (IOException moveFailure) {
            Path staging = target.resolveSibling(target.getFileName() + ".part");
            try {
                Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException copyFailure) {
                Files.deleteIfExists(staging);
                copyFailure.addSuppressed(moveFailure);
                throw copyFailure;
            }
        }
    }

    /**
     * 构造任务文件路径并再次验证没有逃逸根目录。
     */
    private Path target(UUID taskId) {
        Path target = root.resolve(taskId + ".bin").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("任务文件路径越界。");
        }
        return target;
    }
}
