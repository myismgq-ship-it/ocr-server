package com.gsafety.ocrtool.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
/** 预案下载、PDF、规则缓存和异步任务配置。 */


@ConfigurationProperties(prefix = "plan")
public class PlanProperties {

    private Document document = new Document();
    /** 远程文档安全下载配置。 */

    private Pdf pdf = new Pdf();
    /** PDF 逐页解析和渲染配置。 */

    private SegmentRules segmentRules = new SegmentRules();
    /** 数据库规则快照缓存配置。 */

    private Task task = new Task();
    /** 异步任务调度、租约和保留策略。 */

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

    /** 远程文档安全边界。 */
        private DataSize maxSize = DataSize.ofMegabytes(50);

        /** 文档最大字节数，与 multipart 上限保持一致。 */
        private List<String> allowedHosts = new ArrayList<>();

        /** 允许下载的业务域名；支持 *.example.com 通配形式。 */
        private List<Integer> allowedPorts = new ArrayList<>(List.of(80, 443));

        /** 允许访问的目标端口。 */
        private int maxRedirects = 5;

        /** 手工跟随重定向的最大次数。 */
        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts;
        }

        public List<Integer> getAllowedPorts() {
            return allowedPorts;
        }

        public void setAllowedPorts(List<Integer> allowedPorts) {
            this.allowedPorts = allowedPorts;
        }

        public int getMaxRedirects() {
            return maxRedirects;
        }

        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }
    }

    public static class Pdf {

    /** PDF 页面限制和 OCR 判定参数。 */
        private int maxPages = 50;

        /** 单文档最大页数。 */
        private int ocrDpi = 200;

        /** 扫描页渲染 DPI。 */
        private int textMinChars = 80;

        /** 是否检测并按列重识别横倒的四级响应表。 */
        private boolean sidewaysTableOcrEnabled = true;

        /** 页面有效文本少于该字符数时转为 OCR。 */
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

        public boolean isSidewaysTableOcrEnabled() {
            return sidewaysTableOcrEnabled;
        }

        public void setSidewaysTableOcrEnabled(boolean sidewaysTableOcrEnabled) {
            this.sidewaysTableOcrEnabled = sidewaysTableOcrEnabled;
        }
    }

    public static class SegmentRules {

    /** 已发布分段规则的本地缓存参数。 */
        private Duration cacheTtl = Duration.ofMinutes(1);

        /** 规则快照缓存有效期。 */
        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }

    public static class Task {

    /** 异步任务执行和数据保留参数。 */
        private String storageDirectory = System.getProperty("java.io.tmpdir") + "/ocr-plan-tasks";
        private int parallelism = 2;
        /** 多实例部署时必须配置为共享目录。 */
        private Duration scanInterval = Duration.ofSeconds(1);
        /** 单实例最大并行任务数。 */
        private Duration heartbeatInterval = Duration.ofSeconds(30);
        /** 队列扫描间隔。 */
        private Duration lease = Duration.ofMinutes(5);
        /** 活动任务心跳间隔，应明显小于 lease。 */
        private Duration failedFileRetention = Duration.ofDays(7);
        /** 成功任务源文件保留时长，用于复核后按新规则回归重跑。*/
        private Duration completedSourceRetention = Duration.ofDays(90);
        /** 超过该时长没有心跳即认为租约失效。 */
        private Duration orphanFileRetention = Duration.ofHours(1);
        /** 失败任务源文件保留时间，用于重试和排查。 */
        private Duration resultRetention = Duration.ofDays(30);
        /** 未被数据库引用的孤儿文件最短保留时间。 */

        /** 成功结果 JSON 保留时间。 */
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

        public Duration getCompletedSourceRetention() {
            return completedSourceRetention;
        }

        public void setCompletedSourceRetention(Duration completedSourceRetention) {
            this.completedSourceRetention = completedSourceRetention;
        }
        public Duration getOrphanFileRetention() {
            return orphanFileRetention;
        }

        public void setOrphanFileRetention(Duration orphanFileRetention) {
            this.orphanFileRetention = orphanFileRetention;
        }

        public Duration getResultRetention() {
            return resultRetention;
        }

        public void setResultRetention(Duration resultRetention) {
            this.resultRetention = resultRetention;
        }
    }
}
