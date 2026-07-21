package com.gsafety.ocrtool.plan;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PlanDigitizeService {

    private static final Logger log = LoggerFactory.getLogger(PlanDigitizeService.class);

    private final DocumentDownloadService downloadService;
    private final DocumentUploadService uploadService;
    private final DocumentParseService parseService;
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

    public PlanDigitizeResponse digitize(String documentUrl) {
        try (DownloadedDocument document = downloadService.download(documentUrl)) {
            return digitizeDocument(document);
        }
    }

    public PlanDigitizeResponse digitize(MultipartFile file) {
        try (DownloadedDocument document = uploadService.upload(file)) {
            return digitizeDocument(document);
        }
    }

    public PlanDigitizeResponse digitizeDocument(DownloadedDocument document) {
        long totalStarted = System.nanoTime();
        long parseStarted = System.nanoTime();
        ParsedDocument parsedDocument = parseService.parse(document);
        long parseMillis = elapsedMillis(parseStarted);
        long segmentStarted = System.nanoTime();
        SegmentResult segmentResult = segmentService.extract(parsedDocument);
        long segmentMillis = elapsedMillis(segmentStarted);
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
        PlanDigitizeResponse response = new PlanDigitizeResponse(
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
                List.copyOf(warnings));
        log.info(
                "预案数字化完成，fileName={}, parseMode={}, parseMs={}, segmentMs={}, totalMs={}",
                parsedDocument.fileName(), parsedDocument.parseMode(), parseMillis, segmentMillis,
                elapsedMillis(totalStarted));
        return response;
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
