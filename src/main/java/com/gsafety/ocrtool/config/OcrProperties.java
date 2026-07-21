package com.gsafety.ocrtool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {

    private Rapid rapid = new Rapid();

    private Image image = new Image();

    private Preprocess preprocess = new Preprocess();

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

    public static class Rapid {

        private boolean enabled = true;

        private String model = "ONNX_PPOCR_V3";

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
    }

    public static class Image {

        private long maxSize = 10 * 1024 * 1024;

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }
    }

    public static class Preprocess {

        private boolean enabled = true;

        private int minReadableSide = 1200;

        private double maxDeskewAngle = 12.0;

        private boolean multiPass = true;

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
    }
}

