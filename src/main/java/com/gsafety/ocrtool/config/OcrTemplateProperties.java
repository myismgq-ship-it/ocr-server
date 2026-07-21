package com.gsafety.ocrtool.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ocr")
public class OcrTemplateProperties {

    private Map<String, Template> templates = new LinkedHashMap<>();

    public Map<String, Template> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<String, Template> templates) {
        this.templates = templates;
    }

    public static class Template {

        private Map<String, Field> fields = new LinkedHashMap<>();

        public Map<String, Field> getFields() {
            return fields;
        }

        public void setFields(Map<String, Field> fields) {
            this.fields = fields;
        }
    }

    public static class Field {

        private List<String> labels = new ArrayList<>();

        private List<String> stopLabels = new ArrayList<>();

        private String direction = "right";

        private boolean mergeWrappedLines;

        private double minConfidence = 0.8;

        private int maxLines = 0;

        public List<String> getLabels() {
            return labels;
        }

        public void setLabels(List<String> labels) {
            this.labels = labels;
        }

        public List<String> getStopLabels() {
            return stopLabels;
        }

        public void setStopLabels(List<String> stopLabels) {
            this.stopLabels = stopLabels;
        }

        public String getDirection() {
            return direction;
        }

        public void setDirection(String direction) {
            this.direction = direction;
        }

        public boolean isMergeWrappedLines() {
            return mergeWrappedLines;
        }

        public void setMergeWrappedLines(boolean mergeWrappedLines) {
            this.mergeWrappedLines = mergeWrappedLines;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public int getMaxLines() {
            return maxLines;
        }

        public void setMaxLines(int maxLines) {
            this.maxLines = maxLines;
        }
    }
}

