package com.gsafety.ocrtool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
/** OCR 引擎、图片限制、预处理和降级策略配置。 */


@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private Rapid rapid = new Rapid();
    /** RapidOCR 本地推理配置。 */

    private Image image = new Image();
    /** 上传图片大小和解码像素限制。 */

    private Preprocess preprocess = new Preprocess();
    /** 图片增强及自适应双通道配置。 */
    private Fallback fallback = new Fallback();
    /** 备用 OCR 引擎降级配置，默认关闭。 */


    public Rapid getRapid() {
        return rapid;
    }

    public void setRapid(Rapid rapid) {
        this.rapid = rapid;
    }

    public Image getImage() {
        return image;
    }

    public void setImage(Image image) {
        this.image = image;
    }

    public Preprocess getPreprocess() {
        return preprocess;
    }

    public void setPreprocess(Preprocess preprocess) {
        this.preprocess = preprocess;
    }

    public Fallback getFallback() {
        return fallback;
    }

    public void setFallback(Fallback fallback) {
        this.fallback = fallback;
    }

    public static class Rapid {
    /** RapidOCR 模型和并行度。 */

        private boolean enabled = true;
        /** 是否启用本地引擎。 */

        private String model = "ONNX_PPOCR_V4";
        /** RapidOCR 模型枚举名称。 */

        private int maxConcurrency = 1;
        /** 同时进入底层推理实例的最大请求数。 */

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }

    public static class Image {
    /** 图片输入资源限制。 */

        private long maxSize = 10 * 1024 * 1024;
        /** 压缩文件最大字节数。 */

        private long maxPixels = 40_000_000L;
        /** 图片解码后的最大像素总数。 */

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public long getMaxPixels() {
            return maxPixels;
        }

        public void setMaxPixels(long maxPixels) {
            this.maxPixels = maxPixels;
        }
    }

    public static class Preprocess {
    /** OpenCV 增强和原图补充识别参数。 */

        private boolean enabled = true;

        private int minReadableSide = 1200;
        /** 小图放大后的最短边目标值。 */

        private double maxDeskewAngle = 12.0;
        /** 允许自动纠偏的最大倾斜角度。 */

        private boolean multiPass = true;
        /** 是否允许低质量结果补跑原图。 */

        private double multiPassMinConfidence = 0.75;
        /** 低于该平均置信度时补跑原图。 */

        private int multiPassMinTextChars = 8;
        /** 有效文本少于该字符数时补跑原图。 */

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinReadableSide() {
            return minReadableSide;
        }

        public void setMinReadableSide(int minReadableSide) {
            this.minReadableSide = minReadableSide;
        }

        public double getMaxDeskewAngle() {
            return maxDeskewAngle;
        }

        public void setMaxDeskewAngle(double maxDeskewAngle) {
            this.maxDeskewAngle = maxDeskewAngle;
        }

        public boolean isMultiPass() {
            return multiPass;
        }

        public void setMultiPass(boolean multiPass) {
            this.multiPass = multiPass;
        }

        public double getMultiPassMinConfidence() {
            return multiPassMinConfidence;
        }

        public void setMultiPassMinConfidence(double multiPassMinConfidence) {
            this.multiPassMinConfidence = multiPassMinConfidence;
        }

        public int getMultiPassMinTextChars() {
            return multiPassMinTextChars;
        }

        public void setMultiPassMinTextChars(int multiPassMinTextChars) {
            this.multiPassMinTextChars = multiPassMinTextChars;
        }
    }

    public static class Fallback {
    /** 备用引擎触发条件。 */

        private boolean enabled;
        /** 是否允许调用备用引擎。 */

        private double minConfidence = 0.55;
        /** 本地结果低于该置信度时触发降级。 */

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }
    }
}

