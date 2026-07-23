package com.gsafety.ocrtool.plan;

import com.gsafety.ocrtool.common.ProcessingMetrics;
import com.gsafety.ocrtool.common.ProcessingProgressListener;
import com.gsafety.ocrtool.common.ProcessingStage;
import com.gsafety.ocrtool.document.DocumentDownloadService;
import com.gsafety.ocrtool.document.DocumentParseService;
import com.gsafety.ocrtool.document.DocumentUploadService;
import com.gsafety.ocrtool.document.DownloadedDocument;
import com.gsafety.ocrtool.document.ParsedDocument;
import com.gsafety.ocrtool.response.PlanDigitizeResponse;
import com.gsafety.ocrtool.response.PlanSectionResponse;
import com.gsafety.ocrtool.response.ResponseLevelSectionResponse;
import com.gsafety.ocrtool.response.ActionGroupResponse;
import com.gsafety.ocrtool.segment.ActionGroupSegment;
import com.gsafety.ocrtool.segment.PlanSegmentService;
import com.gsafety.ocrtool.segment.ResponseLevelSegment;
import com.gsafety.ocrtool.segment.SegmentResult;
import com.gsafety.ocrtool.segment.SegmentSection;
import com.gsafety.ocrtool.segment.SegmentRules;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预案数字化的主编排服务。
 *
 * <p>统一远程下载/上传、文档解析、规则快照分段、响应映射、进度和耗时指标。</p>
 */
@Service
public class PlanDigitizeService {

    private static final Logger log = LoggerFactory.getLogger(PlanDigitizeService.class);

    /** 受 SSRF 防护约束的远程文档入口。 */
    private final DocumentDownloadService downloadService;
    /** multipart 文档校验和临时文件入口。 */
    private final DocumentUploadService uploadService;
    /** 根据真实文件类型选择 Word/PDF 解析器。 */
    private final DocumentParseService parseService;
    /** 使用单次不可变规则快照提取业务章节。 */
    private final PlanSegmentService segmentService;

    public PlanDigitizeService(
            DocumentDownloadService downloadService,
            DocumentUploadService uploadService,
            DocumentParseService parseService,
            PlanSegmentService segmentService) {
        this.downloadService = downloadService;
        this.uploadService = uploadService;
        this.parseService = parseService;
        this.segmentService = segmentService;
    }

    /** 按远程 URL 同步解析预案。 */
    public PlanDigitizeResponse digitize(String documentUrl) {
        return digitize(documentUrl, ProcessingProgressListener.NOOP);
    }

    /**
     * 按远程 URL 解析预案，并向异步任务上报阶段进度。
     */
    public PlanDigitizeResponse digitize(
            String documentUrl, ProcessingProgressListener progressListener) {
        ProcessingProgressListener listener = listener(progressListener);
        listener.onProgress(ProcessingStage.DOWNLOAD, 5);
        try (DownloadedDocument document = downloadService.download(documentUrl)) {
            return digitizeDocument(document, listener);
        }
    }

    /** 按上传文件同步解析预案。 */
    public PlanDigitizeResponse digitize(MultipartFile file) {
        return digitize(file, ProcessingProgressListener.NOOP);
    }

    /**
     * 按上传文件解析预案，并向异步任务上报阶段进度。
     */
    public PlanDigitizeResponse digitize(
            MultipartFile file, ProcessingProgressListener progressListener) {
        try (DownloadedDocument document = uploadService.upload(file)) {
            return digitizeDocument(document, listener(progressListener));
        }
    }

    /**
     * 使用指定规则快照解析上传样本，供未发布规则的隔离测试使用。
     *
     * <p>该入口不会发布规则，也不会刷新线上规则缓存。</p>
     */
    public PlanDigitizeResponse digitize(MultipartFile file, SegmentRules rules) {
        try (DownloadedDocument document = uploadService.upload(file)) {
            return digitizeDocument(document, ProcessingProgressListener.NOOP, rules);
        }
    }

    /**
     * 使用指定规则快照调试临时上传的样本文档。
     *
     * <p>调试结果额外保留解析块和分段中间结果，仅供管理端解释命中过程；上传临时文件在
     * 请求结束时自动清理，不会写入异步任务和规则版本表。</p>
     */
    public PlanDigitizeDebugRun debug(MultipartFile file, SegmentRules rules) {
        try (DownloadedDocument document = uploadService.upload(file)) {
            return debugDocument(document, rules);
        }
    }

    /** 使用现有 SSRF 防护下载远程文档后执行规则调试。 */
    public PlanDigitizeDebugRun debug(String documentUrl, SegmentRules rules) {
        try (DownloadedDocument document = downloadService.download(documentUrl)) {
            return debugDocument(document, rules);
        }
    }

    /** 调试已准备好的文档；调用方负责决定该文档是否为需要保留的任务源文件。 */
    public PlanDigitizeDebugRun debugDocument(DownloadedDocument document, SegmentRules rules) {
        ParsedDocument parsedDocument = parseService.parse(document);
        SegmentResult segmentResult = segmentService.extract(parsedDocument, rules);
        return new PlanDigitizeDebugRun(
                toResponse(parsedDocument, segmentResult), parsedDocument, segmentResult);
    }
    /** 解析已准备好的文档，供异步上传任务复用。 */
    public PlanDigitizeResponse digitizeDocument(DownloadedDocument document) {
        return digitizeDocument(document, ProcessingProgressListener.NOOP);
    }

    /**
     * 解析已准备好的文档，并持久化任务阶段进度。
     */
    public PlanDigitizeResponse digitizeDocument(
            DownloadedDocument document, ProcessingProgressListener progressListener) {
        return digitizeDocument(document, progressListener, null);
    }

    private PlanDigitizeResponse digitizeDocument(
            DownloadedDocument document,
            ProcessingProgressListener progressListener,
            SegmentRules rules) {
        ProcessingProgressListener listener = listener(progressListener);
        long totalStarted = System.nanoTime();
        listener.onProgress(ProcessingStage.PARSE, 15);
        // 文档解析阶段可能内部切换到 OCR，解析器通过同一监听器细化进度。
        long parseStarted = System.nanoTime();
        ParsedDocument parsedDocument = listener == ProcessingProgressListener.NOOP
                ? parseService.parse(document)
                : parseService.parse(document, listener);
        long parseMillis = elapsedMillis(parseStarted);
        ProcessingMetrics.record("parse", parseStarted);
        listener.onProgress(ProcessingStage.SEGMENT, 75);
        // 未显式传入规则时仅在这里读取一次 provider 快照，保证整份文档规则一致。
        long segmentStarted = System.nanoTime();
        SegmentResult segmentResult = rules == null
                ? segmentService.extract(parsedDocument)
                : segmentService.extract(parsedDocument, rules);
        long segmentMillis = elapsedMillis(segmentStarted);
        ProcessingMetrics.record("segment", segmentStarted);
        PlanDigitizeResponse response = toResponse(parsedDocument, segmentResult);
        listener.onProgress(ProcessingStage.PERSIST, 95);
        log.info(
        // PERSIST 表示结构化结果已经准备完毕，真正数据库提交由任务 Worker 完成。
                "预案数字化完成，fileName={}, parseMode={}, parseMs={}, segmentMs={}, totalMs={}",
                parsedDocument.fileName(), parsedDocument.parseMode(), parseMillis, segmentMillis,
                elapsedMillis(totalStarted));
        ProcessingMetrics.record("total", totalStarted);
        return response;
    }

    /** 将内部解析和分段对象映射为稳定的 REST 结构。 */
    private PlanDigitizeResponse toResponse(ParsedDocument parsedDocument, SegmentResult segmentResult) {
        List<String> warnings = new ArrayList<>();
        warnings.addAll(parsedDocument.warnings());
        warnings.addAll(segmentResult.warnings());
        if (segmentResult.commandSystem() == null) {
            warnings.add("未识别到指挥体系内容。");
        }
        List<ResponseLevelSectionResponse> warningResponses = segmentResult.warningResponses().stream()
                .map(this::toResponseLevelSection)
                .toList();
        List<ResponseLevelSectionResponse> emergencyResponses = segmentResult.emergencyResponses().stream()
                .map(this::toResponseLevelSection)
                .toList();
        return new PlanDigitizeResponse(
                parsedDocument.fileName(),
                parsedDocument.fileType().name(),
                parsedDocument.parseMode().name(),
                toPlanSection(segmentResult.commandSystem()),
                emergencyResponses.stream()
                        .filter(levelResponse -> !"MISSING".equals(levelResponse.status()))
                        .toList(),
                warningResponses,
                emergencyResponses,
                segmentResult.actionGroups().stream().map(this::toActionGroup).toList(),
                List.copyOf(warnings),
                segmentResult.ruleVersion());
    }
    private ProcessingProgressListener listener(ProcessingProgressListener progressListener) {
        return progressListener == null ? ProcessingProgressListener.NOOP : progressListener;
    }

    private PlanSectionResponse toPlanSection(SegmentSection section) {
        if (section == null) {
            return null;
        }
        return new PlanSectionResponse(
                section.key(),
                section.title(),
                section.content(),
                section.sourcePages(),
                section.matchedBy().name(),
                section.matchEvidence());
    }

    private ResponseLevelSectionResponse toResponseLevelSection(SegmentSection section) {
        ResponseContentParts parts = splitResponseContent(section.content());
        return new ResponseLevelSectionResponse(
                section.key(),
                section.level(),
                section.title(),
                parts.content(),
                parts.responseMeasures(),
                section.sourcePages(),
                section.matchedBy().name(),
                section.matchEvidence());
    }

    private ResponseLevelSectionResponse toResponseLevelSection(ResponseLevelSegment section) {
        List<Integer> sourcePages = new ArrayList<>();
        sourcePages.addAll(section.conditionSourcePages());
        section.measureSourcePages().stream().filter(page -> !sourcePages.contains(page)).forEach(sourcePages::add);
        return new ResponseLevelSectionResponse(
                section.key(),
                section.level(),
                section.title(),
                section.activationConditions(),
                section.responseMeasures(),
                List.copyOf(sourcePages),
                section.matchedBy().name(),
                section.matchEvidence(),
                section.category(),
                section.status(),
                section.colorKey(),
                section.colorName(),
                section.activationConditions(),
                section.directResponseMeasures(),
                section.inheritedFromKeys(),
                section.conditionSourcePages(),
                section.measureSourcePages());
    }

    private ActionGroupResponse toActionGroup(ActionGroupSegment group) {
        return new ActionGroupResponse(
                group.key(),
                group.name(),
                group.leadOrganizations(),
                group.memberOrganizations(),
                group.responsibilities(),
                group.rawContent(),
                group.sourcePages(),
                group.matchedBy().name(),
                group.matchEvidence());
    }

    private ResponseContentParts splitResponseContent(String content) {
        if (!StringUtils.hasText(content)) {
            return new ResponseContentParts(content, null);
        }
        List<String> lines = content.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        int explicitIndex = firstLineIndex(lines, "响应措施");
        if (explicitIndex >= 0) {
            return splitAt(lines, explicitIndex, explicitIndex + 1);
        }
        int emergencyMeasureIndex = firstContainingLineIndex(lines, "应急措施");
        if (emergencyMeasureIndex >= 0) {
            return splitAt(lines, emergencyMeasureIndex, emergencyMeasureIndex);
        }
        int approvedIndex = firstApprovedResponseLineIndex(lines);
        if (approvedIndex >= 0 && approvedIndex + 1 < lines.size()) {
            return splitAt(lines, approvedIndex + 1, approvedIndex + 1);
        }
        return new ResponseContentParts(content, null);
    }

    private int firstLineIndex(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); i++) {
            if (target.equals(lines.get(i).replaceAll("\\s+", ""))) {
                return i;
            }
        }
        return -1;
    }

    private int firstContainingLineIndex(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(target)) {
                return i;
            }
        }
        return -1;
    }

    private int firstApprovedResponseLineIndex(List<String> lines) {
        Pattern approved = Pattern.compile(".*(决定|批准|启动).{0,20}[ⅠⅡⅢⅣ一二三四1-4]级.*响应.*");
        for (int i = 0; i < lines.size(); i++) {
            if (approved.matcher(lines.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    private String joinAfter(List<String> lines, int start) {
        if (start < 0 || start >= lines.size()) {
            return null;
        }
        String value = String.join("\n", lines.subList(start, lines.size())).trim();
        return StringUtils.hasText(value) ? value : null;
    }

    private ResponseContentParts splitAt(List<String> lines, int contentEndExclusive, int measuresStart) {
        String mainContent = contentEndExclusive <= 0
                ? null
                : String.join("\n", lines.subList(0, contentEndExclusive)).trim();
        String responseMeasures = joinAfter(lines, measuresStart);
        return new ResponseContentParts(
                StringUtils.hasText(mainContent) ? mainContent : null,
                responseMeasures);
    }

    private record ResponseContentParts(String content, String responseMeasures) {
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
