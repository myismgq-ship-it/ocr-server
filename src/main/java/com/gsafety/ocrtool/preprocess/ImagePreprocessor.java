package com.gsafety.ocrtool.preprocess;

import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;
/**
 * 图片校验和预处理扩展点，返回值负责统一管理临时资源生命周期。
 */

public interface ImagePreprocessor {

    PreprocessedImage preprocess(MultipartFile file);
    /** 预处理 multipart 上传图片。 */

    default PreprocessedImage preprocess(
    /**
     * 预处理已落盘图片；默认实现显式拒绝，支持者需覆盖该方法。
     */
            Path sourcePath,
            String fileName,
            String contentType,
            long fileSize,
            boolean deleteSourceOnClose) {
        throw new UnsupportedOperationException("当前图片预处理器不支持文件路径输入。");
    }
}

