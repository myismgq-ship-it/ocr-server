package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.document.DocumentBlock;
import com.gsafety.ocrtool.document.DocumentFileType;
import com.gsafety.ocrtool.document.DownloadedDocument;
import com.gsafety.ocrtool.plan.PlanDigitizeDebugRun;
import com.gsafety.ocrtool.plan.PlanDigitizeService;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTask;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskRepository;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskSourceType;
import com.gsafety.ocrtool.plan.task.PlanDigitizeTaskStatus;
import com.gsafety.ocrtool.plan.task.PlanTaskStorageService;
import com.gsafety.ocrtool.response.PlanSectionResponse;
import com.gsafety.ocrtool.response.ResponseLevelSectionResponse;
import com.gsafety.ocrtool.segment.DatabaseSegmentRuleProvider;
import com.gsafety.ocrtool.segment.SegmentRuleRepository;
import com.gsafety.ocrtool.segment.SegmentRules;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端规则命中调试服务。
 *
 * <p>服务按需重新解析一份文档并把分段结果映射为中文可读的候选链路，不修改任务结果、
 * 不发布规则，也不持久化文档片段。</p>
 */
@Service
public class PlanRuleDebugService {

    /** 单条规则最多展示的候选数量，限制响应体大小。 */
    private static final int MAX_CANDIDATES = 10;
    /** 原文片段最大字符数，避免把整段敏感正文带入管理端响应。 */
    private static final int MAX_SNIPPET_LENGTH = 200;

    private final PlanRuleRevisionService revisionService;
    private final PlanDigitizeService digitizeService;
    private final PlanDigitizeTaskRepository taskRepository;
    private final PlanTaskStorageService storageService;
    private final DatabaseSegmentRuleProvider ruleProvider;
    private final SegmentRuleRepository ruleRepository;

    public PlanRuleDebugService(
            PlanRuleRevisionService revisionService,
            PlanDigitizeService digitizeService,
            PlanDigitizeTaskRepository taskRepository,
            PlanTaskStorageService storageService,
            DatabaseSegmentRuleProvider ruleProvider,
            SegmentRuleRepository ruleRepository) {
        this.revisionService = revisionService;
        this.digitizeService = digitizeService;
        this.taskRepository = taskRepository;
        this.storageService = storageService;
        this.ruleProvider = ruleProvider;
        this.ruleRepository = ruleRepository;
    }

    /** Returns the database rule source actually used by the runtime parser. */
    public PlanActiveRuleResponse activeRules() {
        SegmentRules rules = ruleProvider.currentRules();
        return new PlanActiveRuleResponse(
                "DATABASE_ACTIVE", "\u5F53\u524D\u6570\u636E\u5E93\u751F\u6548\u89C4\u5219", rules.version(), ruleRepository.findEnabledRuleDefinitions());
    }

    /** Debugs a temporary upload with the current active database rules. */
    public PlanRuleDebugResponse debugActiveFile(MultipartFile file) {
        SegmentRules rules = ruleProvider.currentRules();
        return response(null, 0, "DATABASE_ACTIVE", "DATABASE_ACTIVE",
                "\u5F53\u524D\u6570\u636E\u5E93\u751F\u6548\u89C4\u5219", rules.version(), ruleRepository.findEnabledRuleDefinitions(),
                digitizeService.debug(file, rules), List.of());
    }

    /** Debugs a terminal task with the current active database rules. */
    public PlanRuleDebugResponse debugActiveTask(PlanRuleDebugTaskRequest request) {
        SegmentRules rules = ruleProvider.currentRules();
        return debugTask(request, rules, null, 0, "DATABASE_ACTIVE", "DATABASE_ACTIVE",
                "\u5F53\u524D\u6570\u636E\u5E93\u751F\u6548\u89C4\u5219", rules.version(), ruleRepository.findEnabledRuleDefinitions());
    }
    public PlanRuleDebugResponse debugFile(UUID revisionId, MultipartFile file) {
        PlanRuleRevisionResponse revision = revisionService.get(revisionId);
        SegmentRules rules = revisionService.snapshot(revisionId);
        return response(revision.revisionId(), revision.revisionNumber(), revision.status(), "REVISION",
                "\u89C4\u5219\u7248\u672C " + revision.revisionNumber(), rules.version(), revision.rules(),
                digitizeService.debug(file, rules), List.of());
    }

    /** Uses an immutable revision snapshot to debug a terminal task. */
    public PlanRuleDebugResponse debugTask(UUID revisionId, PlanRuleDebugTaskRequest request) {
        PlanRuleRevisionResponse revision = revisionService.get(revisionId);
        SegmentRules rules = revisionService.snapshot(revisionId);
        return debugTask(request, rules, revision.revisionId(), revision.revisionNumber(), revision.status(),
                "REVISION", "\u89C4\u5219\u7248\u672C " + revision.revisionNumber(), rules.version(), revision.rules());
    }

    /** Runs a task against the supplied immutable rule snapshot. */
    private PlanRuleDebugResponse debugTask(
            PlanRuleDebugTaskRequest request,
            SegmentRules rules,
            UUID revisionId,
            int revisionNumber,
            String revisionStatus,
            String ruleSource,
            String ruleLabel,
            String ruleVersion,
            List<PlanRuleDefinition> ruleDefinitions) {
        if (request == null || !StringUtils.hasText(request.planId()) || request.taskId() == null) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "INVALID_DEBUG_TASK", "\u8BF7\u9009\u62E9\u9884\u6848\u548C\u5386\u53F2\u4EFB\u52A1\u3002");
        }
        PlanDigitizeTask task = taskRepository.findByPlanAndTaskId(request.planId().trim(), request.taskId())
                .orElseThrow(() -> new OcrException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "\u672A\u627E\u5230\u6307\u5B9A\u5386\u53F2\u4EFB\u52A1\u3002"));
        if (!isTerminal(task.status())) {
            throw new OcrException(HttpStatus.CONFLICT, "TASK_NOT_DEBUGGABLE", "\u4EC5\u5DF2\u5B8C\u6210\u3001\u5931\u8D25\u6216\u5DF2\u53D6\u6D88\u7684\u4EFB\u52A1\u53EF\u4EE5\u8C03\u8BD5\u3002");
        }
        if (task.sourceType() == PlanDigitizeTaskSourceType.URL) {
            PlanDigitizeDebugRun run = digitizeService.debug(task.sourceUrl(), rules);
            return response(revisionId, revisionNumber, revisionStatus, ruleSource, ruleLabel, ruleVersion,
                    ruleDefinitions, run, List.of("\u8FDC\u7A0B URL \u5DF2\u91CD\u65B0\u4E0B\u8F7D\uFF0C\u5F53\u524D\u5185\u5BB9\u53EF\u80FD\u4E0E\u5386\u53F2\u4EFB\u52A1\u6267\u884C\u65F6\u4E0D\u540C\u3002"));
        }
        Path sourcePath = storageService.requireExistingSource(task);
        DownloadedDocument document = new DownloadedDocument(
                sourcePath, task.fileName(), task.contentType(), task.fileSize() == null ? 0 : task.fileSize(),
                DocumentFileType.valueOf(task.fileType()));
        return response(revisionId, revisionNumber, revisionStatus, ruleSource, ruleLabel, ruleVersion,
                ruleDefinitions, digitizeService.debugDocument(document, rules), List.of());
    }

    private PlanRuleDebugResponse response(
            UUID revisionId,
            int revisionNumber,
            String revisionStatus,
            String ruleSource,
            String ruleLabel,
            String ruleVersion,
            List<PlanRuleDefinition> ruleDefinitions,
            PlanDigitizeDebugRun run,
            List<String> additionalWarnings) {
        List<RuleDebugTraceResponse> traces = ruleDefinitions.stream()
                .sorted(Comparator.comparingInt(PlanRuleDefinition::groupOrder)
                        .thenComparingInt(PlanRuleDefinition::aliasOrder))
                .map(rule -> trace(rule, run))
                .toList();
        int matched = (int) traces.stream().filter(trace -> "MATCHED".equals(trace.status())).count();
        int fallback = (int) traces.stream().filter(trace -> "FALLBACK".equals(trace.status())).count();
        int missing = (int) traces.stream().filter(trace -> "MISSING".equals(trace.status())).count();
        List<String> warnings = new ArrayList<>(run.result().warnings());
        warnings.addAll(additionalWarnings);
        return new PlanRuleDebugResponse(
                revisionId,
                revisionNumber,
                revisionStatus,
                ruleSource,
                ruleLabel,
                ruleVersion,
                run.result(),
                new RuleDebugSummaryResponse(
                        traces.size(), matched, fallback, missing, run.parsedDocument().blocks().size()),
                traces,
                List.copyOf(warnings));
    }

    /** 将现有规则和解析块转换为可解释的候选链路。 */
    private RuleDebugTraceResponse trace(PlanRuleDefinition rule, PlanDigitizeDebugRun run) {
        List<RuleDebugCandidateResponse> candidates = candidates(rule, run.parsedDocument().blocks());
        if (!rule.enabled()) {
            return new RuleDebugTraceResponse(
                    rule.ruleType(), rule.ruleCode(), rule.canonicalName(), "DISABLED", null, null,
                    "该规则在所选版本中未启用，不参与本次解析。", candidates);
        }
        return switch (rule.ruleType()) {
            case "COMMAND" -> sectionTrace(rule, candidates, run.result().commandSystem(), "指挥体系");
            case "RESPONSE", "WARNING" -> levelTrace(rule, candidates, run, rule.ruleType());
            case "SECTION" -> scopeTrace(rule, candidates);
            case "MARKER" -> markerTrace(rule, candidates, run);
            case "TAIL" -> tailTrace(rule, candidates);
            default -> new RuleDebugTraceResponse(
                    rule.ruleType(), rule.ruleCode(), rule.canonicalName(), "MISSING", null, null,
                    "不支持的规则类型。", candidates);
        };
    }

    private RuleDebugTraceResponse sectionTrace(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates,
            PlanSectionResponse section,
            String targetName) {
        if (section == null) {
            return missing(rule, candidates, "未提取到" + targetName + "，规则别名未形成有效章节。 ");
        }
        if ("CONTEXT_FALLBACK".equals(section.matchedBy())) {
            return fallback(rule, candidates, section.matchedBy(), "未找到明确标题，使用上下文兜底结果。 ");
        }
        RuleDebugCandidateResponse selected = firstUsable(candidates);
        return matched(rule, candidates, selected, section.matchedBy(), "已提取" + targetName + "。 ");
    }

    private RuleDebugTraceResponse levelTrace(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates,
            PlanDigitizeDebugRun run,
            String type) {
        List<ResponseLevelSectionResponse> levels = "WARNING".equals(type)
                ? run.result().warningResponses()
                : run.result().emergencyResponses();
        ResponseLevelSectionResponse level = levels.stream()
                .filter(item -> rule.ruleCode().equals(item.key()))
                .findFirst()
                .orElse(null);
        if (level == null || "MISSING".equals(level.status())) {
            return missing(rule, candidates, "未提取到" + rule.canonicalName() + "。 ");
        }
        if ("CONTEXT_FALLBACK".equals(level.matchedBy())) {
            return fallback(rule, candidates, level.matchedBy(), "使用上下文兜底识别到" + rule.canonicalName() + "。 ");
        }
        return matched(rule, candidates, firstUsable(candidates), level.matchedBy(), "已提取" + rule.canonicalName() + "。 ");
    }

    private RuleDebugTraceResponse scopeTrace(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates) {
        long usable = candidates.stream().filter(this::usable).count();
        if (usable == 1) {
            return matched(rule, candidates, firstUsable(candidates), "SECTION_SCOPE", "章节范围唯一，作为扫描范围提示。 ");
        }
        if (usable > 1) {
            return fallback(rule, candidates, "FULL_DOCUMENT", "发现多个章节候选，为避免漏识别已回退全文扫描。 ");
        }
        return fallback(rule, candidates, "FULL_DOCUMENT", "未找到明确章节范围，已回退全文扫描。 ");
    }

    private RuleDebugTraceResponse markerTrace(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates,
            PlanDigitizeDebugRun run) {
        boolean used = textContainsMarker(run, rule.alias());
        if (used && firstUsable(candidates) != null) {
            return matched(rule, candidates, firstUsable(candidates), "MARKER", "标记进入条件、措施或行动组内容边界判断。 ");
        }
        if (firstUsable(candidates) != null) {
            return fallback(rule, candidates, "MARKER", "发现标记候选，但未形成稳定内容边界。 ");
        }
        return missing(rule, candidates, "全文未发现可用标记。 ");
    }

    private RuleDebugTraceResponse tailTrace(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates) {
        if (firstUsable(candidates) != null) {
            return matched(rule, candidates, firstUsable(candidates), "TAIL", "结束边界标题可用于截断响应内容。 ");
        }
        return missing(rule, candidates, "未发现结束边界标题。 ");
    }

    private RuleDebugTraceResponse matched(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates,
            RuleDebugCandidateResponse selected,
            String matchedBy,
            String reason) {
        return new RuleDebugTraceResponse(
                rule.ruleType(), rule.ruleCode(), rule.canonicalName(), "MATCHED",
                selected == null ? null : selected.alias(), matchedBy, reason, candidates);
    }

    private RuleDebugTraceResponse fallback(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates,
            String matchedBy,
            String reason) {
        return new RuleDebugTraceResponse(
                rule.ruleType(), rule.ruleCode(), rule.canonicalName(), "FALLBACK", null, matchedBy, reason, candidates);
    }

    private RuleDebugTraceResponse missing(
            PlanRuleDefinition rule,
            List<RuleDebugCandidateResponse> candidates,
            String reason) {
        return new RuleDebugTraceResponse(
                rule.ruleType(), rule.ruleCode(), rule.canonicalName(), "MISSING", null, null, reason, candidates);
    }

    private List<RuleDebugCandidateResponse> candidates(PlanRuleDefinition rule, List<DocumentBlock> blocks) {
        if (!StringUtils.hasText(rule.alias())) {
            return List.of();
        }
        String alias = normalize(rule.alias());
        List<RuleDebugCandidateResponse> candidates = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            DocumentBlock block = blocks.get(index);
            if (!normalize(block.text()).contains(alias)) {
                continue;
            }
            candidates.add(new RuleDebugCandidateResponse(
                    index,
                    block.page(),
                    rule.alias(),
                    snippet(block.text()),
                    disposition(rule.ruleType(), block)));
        }
        return candidates.stream().limit(MAX_CANDIDATES).toList();
    }

    private String disposition(String ruleType, DocumentBlock block) {
        String text = normalize(block.text());
        if (text.contains("目录")) {
            return "目录项，已排除";
        }
        if (text.contains("流程图") || text.contains("示意图") || text.contains("组织图") || text.contains("框架图")) {
            return "流程图或示意图，已排除";
        }
        if (("COMMAND".equals(ruleType) || "RESPONSE".equals(ruleType)
                || "WARNING".equals(ruleType) || "SECTION".equals(ruleType))
                && !block.heading() && !block.table()) {
            return "正文包含别名，非标题候选";
        }
        if (block.table()) {
            return "表格候选";
        }
        if (block.heading()) {
            return "正文标题候选";
        }
        return "正文候选";
    }

    private boolean textContainsMarker(PlanDigitizeDebugRun run, String marker) {
        if (!StringUtils.hasText(marker)) {
            return false;
        }
        String normalized = normalize(marker);
        return run.parsedDocument().blocks().stream().anyMatch(block -> normalize(block.text()).contains(normalized));
    }

    private RuleDebugCandidateResponse firstUsable(List<RuleDebugCandidateResponse> candidates) {
        return candidates.stream().filter(this::usable).findFirst().orElse(null);
    }

    private boolean usable(RuleDebugCandidateResponse candidate) {
        return candidate != null && !candidate.disposition().contains("已排除")
                && !candidate.disposition().contains("非标题");
    }

    private boolean isTerminal(PlanDigitizeTaskStatus status) {
        return status == PlanDigitizeTaskStatus.COMPLETED
                || status == PlanDigitizeTaskStatus.FAILED
                || status == PlanDigitizeTaskStatus.CANCELLED;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String snippet(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String compact = value.trim().replaceAll("\\s+", " ");
        return compact.length() <= MAX_SNIPPET_LENGTH ? compact : compact.substring(0, MAX_SNIPPET_LENGTH) + "…";
    }
}
