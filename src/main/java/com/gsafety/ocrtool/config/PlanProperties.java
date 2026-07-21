package com.gsafety.ocrtool.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "plan")
public class PlanProperties {

    private Document document = new Document();

    private Pdf pdf = new Pdf();

    private SegmentRules segmentRules = new SegmentRules();

    private Task task = new Task();

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public Pdf getPdf() {
        return pdf;
    }

    public void setPdf(Pdf pdf) {
        this.pdf = pdf;
    }

    public SegmentRules getSegmentRules() {
        return segmentRules;
    }

    public void setSegmentRules(SegmentRules segmentRules) {
        this.segmentRules = segmentRules;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public static class Document {

        private DataSize maxSize = DataSize.ofMegabytes(50);

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }
    }

    public static class Pdf {

        private int maxPages = 50;

        private int ocrDpi = 200;

        private int textMinChars = 80;

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public int getOcrDpi() {
            return ocrDpi;
        }

        public void setOcrDpi(int ocrDpi) {
            this.ocrDpi = ocrDpi;
        }

        public int getTextMinChars() {
            return textMinChars;
        }

        public void setTextMinChars(int textMinChars) {
            this.textMinChars = textMinChars;
        }
    }

    public static class SegmentRules {

        private Duration cacheTtl = Duration.ofMinutes(1);

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }

    public static class Task {

        private String storageDirectory = System.getProperty("java.io.tmpdir") + "/ocr-plan-tasks";
        private int parallelism = 2;
        private Duration scanInterval = Duration.ofSeconds(1);
        private Duration heartbeatInterval = Duration.ofSeconds(30);
        private Duration lease = Duration.ofMinutes(5);
        private Duration failedFileRetention = Duration.ofDays(7);

        public String getStorageDirectory() {
            return storageDirectory;
        }

        public void setStorageDirectory(String storageDirectory) {
            this.storageDirectory = storageDirectory;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public Duration getScanInterval() {
            return scanInterval;
        }

        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public Duration getFailedFileRetention() {
            return failedFileRetention;
        }

        public void setFailedFileRetention(Duration failedFileRetention) {
            this.failedFileRetention = failedFileRetention;
        }
    }
}
