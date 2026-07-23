package com.gsafety.ocrtool.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
/** 静态 YAML OCR 模板配置，数据库已发布模板可覆盖同编码配置。 */


@ConfigurationProperties(prefix = "ocr")
public class OcrTemplateProperties {

    private Map<String, Template> templates = new LinkedHashMap<>();
    /** 模板编码到完整模板定义的映射。 */

    public Map<String, Template> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<String, Template> templates) {
        this.templates = templates;
    }

    public static class Template {

    /** 单个证照模板。 */
        private Map<String, Field> fields = new LinkedHashMap<>();

        /** 结构化字段定义。 */
        public Map<String, Field> getFields() {
            return fields;
        }

        private String processor;

        /** 可选模板级专用处理器编码。 */
        public void setFields(Map<String, Field> fields) {
            this.fields = fields;
        }

        public String getProcessor() {
            return processor;
        }

        public void setProcessor(String processor) {
            this.processor = processor;
        }
    }

    public static class Field {

        private List<String> labels = new ArrayList<>();

        private List<String> stopLabels = new ArrayList<>();
    /** 单个字段的标签、空间方向和质量约束。 */


        /** 可命中的标签别名。 */
        private String direction = "right";

        /** 遇到这些标签时停止继续收集字段值。 */
        private boolean mergeWrappedLines;

        private double minConfidence = 0.8;
        /** 值位于标签 right 或 below 方向。 */

        private int maxLines = 0;
        /** 是否合并视觉上属于同一字段的换行文本。 */

        public List<String> getLabels() {
        /** 字段最低可接受置信度。 */
            return labels;
        }
        /** 最大收集行数，0 表示不限制。 */

        public void setLabels(List<String> labels) {
            this.labels = labels;
        }

        public List<String> getStopLabels() {
            return stopLabels;
        }
        private String pattern;
        /** 字段值格式正则，不匹配时状态为 INVALID。 */


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

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }
    }
}
