package com.gsafety.ocrtool.engine;

import com.benjaminwan.ocrlibrary.TextBlock;
import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.recognition.OcrPoint;
import com.gsafety.ocrtool.recognition.OcrResult;
import com.gsafety.ocrtool.config.OcrProperties;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.ParamConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RapidOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(RapidOcrEngine.class);
    private static final String DEFAULT_MODEL = "ONNX_PPOCR_V3";

    private final OcrProperties properties;
    private volatile InferenceEngine engine;

    public RapidOcrEngine(OcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "rapidocr";
    }

    @Override
    public OcrResult recognize(Path imagePath) {
        if (!properties.getRapid().isEnabled()) {
            throw new OcrException(HttpStatus.SERVICE_UNAVAILABLE, "OCR_DISABLED", "OCR 服务未启用。");
        }
        try {
            ParamConfig paramConfig = ParamConfig.getDefaultConfig();
            paramConfig.setDoAngle(true);
            paramConfig.setMostAngle(true);
            synchronized (this) {
                return toResult(getEngine().runOcr(imagePath.toString(), paramConfig));
            }
        } catch (OcrException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new OcrException(HttpStatus.INTERNAL_SERVER_ERROR, "OCR_ENGINE_FAILED", "OCR 识别失败。", ex);
        }
    }

    private InferenceEngine getEngine() {
        InferenceEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (engine == null) {
                Model model = parseModel(properties.getRapid().getModel());
                log.info("初始化 RapidOCR 引擎，model={}", model);
                engine = InferenceEngine.getInstance(model);
            }
            return engine;
        }
    }

    private Model parseModel(String model) {
        String value = StringUtils.hasText(model) ? model.trim() : DEFAULT_MODEL;
        try {
            return Model.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new OcrException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_OCR_MODEL", "OCR 模型配置不正确：" + model, ex);
        }
    }

    private OcrResult toResult(com.benjaminwan.ocrlibrary.OcrResult result) {
        List<OcrLine> lines = new ArrayList<>();
        if (result.getTextBlocks() != null) {
            for (TextBlock block : result.getTextBlocks()) {
                String text = block.getText();
                if (StringUtils.hasText(text)) {
                    lines.add(new OcrLine(text.trim(), confidence(block), box(block)));
                }
            }
        }
        String text = StringUtils.hasText(result.getStrRes()) ? result.getStrRes().trim() : "";
        return new OcrResult(text, lines);
    }

    private List<OcrPoint> box(TextBlock block) {
        if (block.getBoxPoint() == null || block.getBoxPoint().isEmpty()) {
            return List.of();
        }
        List<OcrPoint> points = new ArrayList<>();
        for (com.benjaminwan.ocrlibrary.Point point : block.getBoxPoint()) {
            points.add(new OcrPoint(point.getX(), point.getY()));
        }
        return points;
    }

    private Double confidence(TextBlock block) {
        float[] scores = block.getCharScores();
        if (scores != null && scores.length > 0) {
            double sum = 0;
            for (float score : scores) {
                sum += score;
            }
            return sum / scores.length;
        }
        return (double) block.getBoxScore();
    }
}

