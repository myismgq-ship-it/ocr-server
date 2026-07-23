package com.gsafety.ocrtool.engine;

import com.gsafety.ocrtool.recognition.OcrResult;
import java.nio.file.Path;

/**
 * 可选备用 OCR 引擎扩展点。
 *
 * <p>实现可能把图片发送到外部服务，因此只有明确配置
 * {@code ocr.fallback.enabled=true} 后才允许调用。</p>
 */
public interface BackupOcrEngine {

    /** 返回日志和响应中使用的引擎名称。 */
    String name();

    /**
     * 识别指定图片。
     *
     * @param imagePath 本地临时图片路径
     */
    OcrResult recognize(Path imagePath);
}
