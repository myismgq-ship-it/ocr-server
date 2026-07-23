package com.gsafety.ocrtool.engine;

import com.gsafety.ocrtool.config.OcrProperties;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Primary
/**
 * 为本地 RapidOCR 增加可配置降级能力的主 OCR 门面。
 *
 * <p>备用引擎默认关闭；只有本地结果缺失、置信度过低或本地引擎异常时才尝试，
 * 避免在未授权情况下把文档发送给外部服务。</p>
 */
@Component
public class FallbackAwareOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(FallbackAwareOcrEngine.class);

    /** 首选的本地 OCR 引擎。 */
    private final RapidOcrEngine primaryEngine;
    private final OcrProperties properties;
    /** 延迟获取备用实现；未部署备用引擎时应用仍可正常启动。 */
    private final ObjectProvider<BackupOcrEngine> backupEngines;

    public FallbackAwareOcrEngine(
            RapidOcrEngine primaryEngine,
            OcrProperties properties,
            ObjectProvider<BackupOcrEngine> backupEngines) {
        this.primaryEngine = primaryEngine;
        this.properties = properties;
        this.backupEngines = backupEngines;
    }

    @Override
    public String name() {
        return primaryEngine.name();
    }

    /**
     * 优先调用本地引擎，并在配置允许且满足降级条件时选择备用引擎。
     *
     * @param imagePath 待识别图片路径
     * @return 本地或备用引擎的识别结果
     */
    @Override
    public OcrResult recognize(Path imagePath) {
        if (!properties.getFallback().isEnabled()) {
            return primaryEngine.recognize(imagePath);
        }
        Optional<BackupOcrEngine> backup = backupEngines.orderedStream().findFirst();
        if (backup.isEmpty()) {
            return primaryEngine.recognize(imagePath);
        }
        try {
            OcrResult primary = primaryEngine.recognize(imagePath);
            if (!shouldFallback(primary)) {
                return primary;
            }
            log.warn("本地 OCR 结果低于降级阈值，调用备用引擎，backup={}", backup.get().name());
            return backup.get().recognize(imagePath);
        } catch (RuntimeException primaryFailure) {
            log.warn("本地 OCR 不可用，调用备用引擎，backup={}", backup.get().name(), primaryFailure);
            return backup.get().recognize(imagePath);
        }
    }

    /**
     * 空结果、无置信度或平均置信度低于阈值时触发降级。
     */
    private boolean shouldFallback(OcrResult result) {
        if (result == null || !StringUtils.hasText(result.text())) {
            return true;
        }
        if (result.lines() == null || result.lines().isEmpty()) {
            return true;
        }
        double average = result.lines().stream()
                .map(OcrLine::confidence)
                .filter(value -> value != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        return average < properties.getFallback().getMinConfidence();
    }
}
