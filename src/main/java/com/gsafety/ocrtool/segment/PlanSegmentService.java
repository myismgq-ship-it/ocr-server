package com.gsafety.ocrtool.segment;

import com.gsafety.ocrtool.document.DocumentBlock;
import com.gsafety.ocrtool.document.ParsedDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 将已解析文档块切分为预案业务结构。
 *
 * <p>规则快照负责章节范围、级别别名、内容标记和继承关系；
 * 代码中的正则只承担通用版式容错和候选评分。</p>
 */
@Service
public class PlanSegmentService {

    /** 行动组标题的通用版式模式，数据库 MARKER 规则用于补充业务入口词。 */
    private static final Pattern ACTION_GROUP_PATTERN = Pattern.compile(
            "(?:[（(]?\\d+[）)]\\s*)?([\\p{IsHan}A-Za-z0-9]{2,24}(?:工作组|保障组|指挥组|协调组|处置组|救援组|专家组|行动组|组))(?=[:：。\\s]|$)");
    /** “下设……等工作组”类行内行动组列表。 */
    private static final Pattern INLINE_GROUPS_PATTERN = Pattern.compile("下设(.{2,120}?)等(?:专项)?工作组");

    /** 默认规则快照提供器。 */
    private final SegmentRuleProvider ruleProvider;

    public PlanSegmentService(SegmentRuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    /**
     * 使用当前已发布规则快照解析一份文档。
     */
    public SegmentResult extract(ParsedDocument document) {
        return extract(document, ruleProvider.currentRules());
    }

    /**
     * 使用调用方指定的不可变规则快照解析文档。
     *
     * @param document 文档解析器生成的有序块
     * @param rules 本次文档全程固定使用的规则快照
     */
    public SegmentResult extract(ParsedDocument document, SegmentRules rules) {
        List<String> warnings = new ArrayList<>();
        SegmentSection commandSystem = findBestSection(
                rules.commandKey(), null, rules.commandAliases(), document.blocks(), warnings, rules)
                .orElse(null);

        List<SegmentSection> responseLevels = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : rules.responseAliases().entrySet()) {
            Optional<SegmentSection> section = findBestSection(
                    rules.responseKeys().get(entry.getKey()),
                    entry.getKey(),
                    entry.getValue(),
                    document.blocks(),
                    warnings,
                    rules);
            if (section.isPresent()) {
                responseLevels.add(section.get());
            } else {
                warnings.add("未识别到" + entry.getKey() + "内容。");
        // SECTION 规则只在范围唯一且边界明确时缩小扫描区域；存在多个候选时回退全文，
        // 避免“响应条件”和“响应措施”分布在不同章节时被错误截断。
            }
        }
        List<DocumentBlock> warningBlocks = scopedBlocks(document.blocks(), "warning_scope", rules);
        List<DocumentBlock> emergencyBlocks = scopedBlocks(document.blocks(), "emergency_scope", rules);
        List<DocumentBlock> actionGroupBlocks = scopedBlocks(document.blocks(), "action_group_scope", rules);
        List<ResponseLevelSegment> warningResponses = extractLevels(
                "WARNING", levelDefinitions(true, rules), warningBlocks, rules, warnings);
        List<ResponseLevelSegment> emergencyResponses = extractLevels(
                "EMERGENCY", levelDefinitions(false, rules), emergencyBlocks, rules, warnings);
        List<ActionGroupSegment> actionGroups = extractActionGroups(actionGroupBlocks, rules);
        return new SegmentResult(
                commandSystem,
                responseLevels,
                warningResponses,
                emergencyResponses,
                actionGroups,
                warnings,
                rules.version());
    }

    /**
     * 根据 SECTION 别名定位一个明确的业务章节。
     *
     * <p>同一个别名可能同时出现在“响应分级”“应急响应”和各级响应子章节中，
     * 因此只有正文中恰好存在一个范围候选时才裁剪；多候选、无候选或范围内没有
     * 正文时均回退全文。SECTION 是召回范围提示，不能成为丢弃其他章节的硬过滤器。</p>
     */
    private List<DocumentBlock> scopedBlocks(
            List<DocumentBlock> blocks, String sectionCode, SegmentRules rules) {
        List<String> aliases = rules.sectionAliases().get(sectionCode);
        if (aliases == null || aliases.isEmpty()) {
            return blocks;
        }
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            String alias = matchingAlias(block.text(), aliases);
            if (alias != null
                    && !isTocBlock(block)
                    && !isDiagramHeading(block.text())
                    && (block.heading() || block.table())
                    && normalize(block.text()).length() <= normalize(alias).length() + 20) {
                candidates.add(i);
            }
        }
        if (candidates.size() != 1) {
            return blocks;
        }
        int start = candidates.get(0);
        DocumentBlock anchor = blocks.get(start);
        int anchorLevel = anchor.headingLevel() > 0 ? anchor.headingLevel() : 2;
        int end = blocks.size();
        for (int i = start + 1; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            boolean nextConfiguredSection = rules.sectionAliases().values().stream()
                    .flatMap(List::stream)
                    .anyMatch(alias -> matchingAlias(block.text(), List.of(alias)) != null)
                    && (block.heading() || block.table());
            if (nextConfiguredSection) {
                end = i;
                break;
            }
            if (block.headingLevel() > 0 && block.headingLevel() <= anchorLevel) {
                end = i;
                break;
            }
        }
        // 只有标题没有正文通常是宽泛别名误命中，继续扫描全文更安全。
        return end > start + 1 ? blocks.subList(start, end) : blocks;
    }

    /** 流程图、示意图和组织图是附件证据，不能作为章节裁剪入口。 */
    private boolean isDiagramHeading(String text) {
        String normalized = normalize(text);
        return normalized.contains("流程图")
                || normalized.contains("示意图")
                || normalized.contains("框架图")
                || normalized.contains("组织图");
    }

    private List<LevelDefinition> levelDefinitions(boolean warning, SegmentRules rules) {
        List<LevelDefinition> definitions = new ArrayList<>();
        String[] levels = warning
                ? new String[] {"一级预警", "二级预警", "三级预警", "四级预警"}
                : new String[] {"一级响应", "二级响应", "三级响应", "四级响应"};
        String[] keys = warning
                ? new String[] {"warning_level_1", "warning_level_2", "warning_level_3", "warning_level_4"}
                : new String[] {"level_1", "level_2", "level_3", "level_4"};
        String[] colorKeys = {"red", "orange", "yellow", "blue"};
        String[] colorNames = {"红色", "橙色", "黄色", "蓝色"};
        List<List<String>> defaults = warning ? defaultWarningAliases() : defaultResponseAliases();
        Map<String, List<String>> configuredAliases = warning ? rules.warningAliases() : rules.responseAliases();
        Map<String, String> configuredKeys = warning ? rules.warningKeys() : rules.responseKeys();
        for (int i = 0; i < 4; i++) {
            List<String> aliases = new ArrayList<>(defaults.get(i));
            for (Map.Entry<String, String> entry : configuredKeys.entrySet()) {
                if (keys[i].equals(entry.getValue())) {
                    aliases.addAll(configuredAliases.getOrDefault(entry.getKey(), List.of()));
                }
            }
            definitions.add(new LevelDefinition(
                    keys[i], levels[i], warning ? colorKeys[i] : null, warning ? colorNames[i] : null,
                    List.copyOf(new LinkedHashSet<>(aliases))));
        }
        return definitions;
    }

    private List<List<String>> defaultWarningAliases() {
        return List.of(
                List.of("一级预警", "Ⅰ级预警", "I级预警", "红色预警", "红色"),
                List.of("二级预警", "Ⅱ级预警", "II级预警", "橙色预警", "橙色"),
                List.of("三级预警", "Ⅲ级预警", "III级预警", "黄色预警", "黄色"),
                List.of("四级预警", "Ⅳ级预警", "IV级预警", "蓝色预警", "蓝色"));
    }

    private List<List<String>> defaultResponseAliases() {
        return List.of(
                List.of("一级响应", "一级应急响应", "Ⅰ级响应", "I级响应", "特别重大响应"),
                List.of("二级响应", "二级应急响应", "Ⅱ级响应", "II级响应", "重大响应"),
                List.of("三级响应", "三级应急响应", "Ⅲ级响应", "III级响应", "较大响应"),
                List.of("四级响应", "四级应急响应", "Ⅳ级响应", "IV级响应", "一般响应"));
    }

    /**
     * 提取四级预警或响应，并分别归类启动条件、直接措施和继承措施。
     *
     * <p>固定返回四级结果，未命中项使用 MISSING，而不是从响应中删除。</p>
     */
    private List<ResponseLevelSegment> extractLevels(
            String category,
            List<LevelDefinition> definitions,
            List<DocumentBlock> blocks,
            SegmentRules rules,
            List<String> warnings) {
        Map<String, MutableLevel> levels = new LinkedHashMap<>();
        definitions.forEach(definition -> levels.put(definition.key(), new MutableLevel(definition)));
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            if (isTocBlock(block) || isFlowchartContext(i, blocks)) {
                continue;
            }
            if (isConditionLevelContinuation(i, blocks)) {
                continue;
            }
            for (LevelDefinition definition : definitions) {
                if (!matchesLevelDefinition(block.text(), definition)) {
                    continue;
                }
                // 预案常把启动条件直接写成“符合……时，启动一级响应”，没有独立的
                // “一级响应”标题。此类正文是有效条件证据，不能按普通级别引用丢弃。
                if (isExplicitActivationCondition(block.text())
                        && (contextKind(category, i, blocks, rules) == ContentKind.CONDITION
                        || containsMultipleLevelDefinitions(block.text(), definitions))
                        && !hasNearbyLevelAnchor(i, blocks, definition)
                        && !isResponseLifecycleReference(block.text())) {
                    levels.get(definition.key()).addCondition(block);
                    continue;
                }
                if (!isPrimaryLevelMention(block, definition, definitions)) {
                    continue;
                }
                int end = findLevelWindowEnd(i, blocks, definitions, rules);
                CandidateParts parts = classifyLevelWindow(category, i, end, blocks, rules);
                String title = containsGroupedLevelMention(block.text(), definition, definitions)
                        ? definition.level()
                        : block.text();
                levels.get(definition.key()).add(block, parts, title);
            }
        }
        if ("WARNING".equals(category)) {
            applyWarningInheritance(levels, definitions, rules);
        }
        List<ResponseLevelSegment> result = new ArrayList<>();
        for (MutableLevel level : levels.values()) {
            ResponseLevelSegment segment = level.toSegment();
            result.add(segment);
            if ("MISSING".equals(segment.status()) && "WARNING".equals(category)) {
                warnings.add("未识别到" + segment.level() + "内容。");
            } else if ("PARTIAL".equals(segment.status())) {
                warnings.add(segment.level() + "仅识别到启动条件或响应措施中的一项，请人工核对。");
            }
        }
        return List.copyOf(result);
    }

    private boolean isPrimaryLevelMention(
            DocumentBlock block, LevelDefinition current, List<LevelDefinition> definitions) {
        if (isResponseLifecycleReference(block.text())) {
            return false;
        }
        // 部分预案不用标题样式，而是以“启动一级应急响应：”作为措施列表入口。
        // 该短句本身不是条件正文，但必须作为级别窗口锚点，否则后续措施会整体漏掉。
        if (isLevelActivationHeading(block.text(), current)) {
            return true;
        }
        if (looksLikeCondition(block.text())) {
            return false;
        }
        if (block.heading() || block.table()) {
            return true;
        }
        if (containsGroupedLevelMention(block.text(), current, definitions)) {
            return true;
        }
        String text = stripSectionNumber(normalize(block.text()));
        return current.aliases().stream().map(this::normalize).anyMatch(text::equals);
    }

    /**
     * 判断正文短行是否承担响应级别标题作用。
     *
     * <p>只接受长度较短、以启动动词开头且完整包含当前级别别名的文本，
     * 避免把“启动响应后及时报告”等普通叙述误判为章节入口。</p>
     */
    private boolean isLevelActivationHeading(String text, LevelDefinition definition) {
        String value = stripSectionNumber(normalize(text));
        if (value.length() > 32 || !value.matches("^(启动|实施|进入|执行).+")) {
            return false;
        }
        return definition.aliases().stream()
                .map(this::normalize)
                .anyMatch(alias -> value.matches("^(启动|实施|进入|执行)" + Pattern.quote(alias) + "$"));
    }

    private boolean containsGroupedLevelMention(
            String text, LevelDefinition current, List<LevelDefinition> definitions) {
        if (matchesCompressedLevelMention(text, current)) {
            return true;
        }
        for (String currentAlias : current.aliases()) {
            for (LevelDefinition other : definitions) {
                if (other.key().equals(current.key())) {
                    continue;
                }
                for (String otherAlias : other.aliases()) {
                    String left = Pattern.quote(currentAlias) + ".{0,4}[、及和与/]" + ".{0,4}" + Pattern.quote(otherAlias);
                    String right = Pattern.quote(otherAlias) + ".{0,4}[、及和与/]" + ".{0,4}" + Pattern.quote(currentAlias);
                    if (Pattern.compile(left + "|" + right).matcher(text).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesLevelDefinition(String text, LevelDefinition definition) {
        return matchingAlias(text, definition.aliases()) != null
                || matchesCompressedLevelMention(text, definition);
    }

    private boolean matchesCompressedLevelMention(String text, LevelDefinition definition) {
        if (!definition.level().endsWith("响应")) {
            return false;
        }
        String numeral = definition.level().substring(0, 1);
        Matcher matcher = Pattern.compile(
                        "([一二三四](?:级)?(?:[、,，/和及与或][一二三四](?:级)?)+)(?:应急)?响应")
                .matcher(normalize(text));
        while (matcher.find()) {
            if (matcher.group(1).contains(numeral)) {
                return true;
            }
        }
        return false;
    }

    private int findLevelWindowEnd(
            int start, List<DocumentBlock> blocks, List<LevelDefinition> definitions, SegmentRules rules) {
        int anchorLevel = blocks.get(start).headingLevel();
        int limit = Math.min(blocks.size(), start + 60);
        for (int i = start + 1; i < limit; i++) {
            DocumentBlock block = blocks.get(i);
            if (blocks.get(start).table() && !block.table()) {
                return i;
            }
            boolean levelHeading = matchesAnyLevel(block.text(), definitions)
                    && !looksLikeCondition(block.text())
                    && !isConditionLevelContinuation(i, blocks)
                    && (block.heading() || block.table() || block.text().length() <= 18 || startsWithNumber(block.text()));
            if (isResponseBoundary(block.text(), rules) || levelHeading) {
                return i;
            }
            if (anchorLevel > 0 && block.headingLevel() > 0 && block.headingLevel() <= anchorLevel && i > start + 1) {
                return i;
            }
        }
        return limit;
    }

    private boolean isConditionLevelContinuation(int index, List<DocumentBlock> blocks) {
        if (index <= 0 || !looksLikeCondition(blocks.get(index - 1).text())) {
            return false;
        }
        // 一整句“符合……条件时，建议启动二级响应”是新的独立条件，
        // 只有“启动二级响应：”这类短行才是上一条件单元的续行。
        if (isExplicitActivationCondition(blocks.get(index).text())) {
            return false;
        }
        String text = normalize(blocks.get(index).text());
        return text.length() <= 24
                && text.contains("响应") && (text.contains("启动") || text.startsWith("动"));
    }

    private boolean matchesAnyLevel(String text, List<LevelDefinition> definitions) {
        return definitions.stream().anyMatch(definition -> matchesLevelDefinition(text, definition));
    }

    /**
     * 判断一段启动条件是否同时覆盖多个响应级别。
     *
     * <p>“特别重大启动一级、重大启动二级……”是公共条件段，应分别写入每个命中级别；
     * 单级普通正文仍由章节上下文决定，避免跨章节吸收条件。</p>
     */
    private boolean containsMultipleLevelDefinitions(String text, List<LevelDefinition> definitions) {
        return definitions.stream().filter(definition -> matchesLevelDefinition(text, definition)).count() > 1;
    }

    /**
     * 判断当前正文是否已经属于紧邻的级别标题窗口。
     *
     * <p>标题后的“符合三级响应条件时，由指挥长宣布启动……”属于该级响应措施，
     * 会在窗口分类阶段处理；这里若再次当作独立条件，会造成条件和措施重复。</p>
     */
    private boolean hasNearbyLevelAnchor(
            int index, List<DocumentBlock> blocks, LevelDefinition definition) {
        for (int i = index - 1; i >= Math.max(0, index - 2); i--) {
            DocumentBlock candidate = blocks.get(i);
            if (matchesLevelDefinition(candidate.text(), definition)
                    && (candidate.heading() || candidate.table() || candidate.text().length() <= 30)) {
                return true;
            }
            if (candidate.heading()) {
                return false;
            }
        }
        return false;
    }

    /**
     * 按数据库 MARKER 规则在一个级别窗口内区分条件和措施。
     */
    private CandidateParts classifyLevelWindow(
            String category, int start, int end, List<DocumentBlock> blocks, SegmentRules rules) {
        List<DocumentBlock> conditions = new ArrayList<>();
        List<DocumentBlock> measures = new ArrayList<>();
        ContentKind kind = contextKind(category, start, blocks, rules);
        for (int i = start; i < end; i++) {
            DocumentBlock block = blocks.get(i);
            String text = block.text().trim();
            if (i == start && (block.heading()
                    || text.length() <= 30 && !text.matches(".*[。；;].*"))) {
                continue;
            }
            InlineContentParts inlineParts = splitInlineConditionAndMeasure(block);
            if (inlineParts != null) {
                // “出现重大状态时启动二级响应，指挥部立即采取……”在同一段中同时
                // 包含条件和措施，必须拆开归类，不能把整段只放进其中一个字段。
                conditions.add(inlineParts.condition());
                measures.add(inlineParts.measure());
                kind = ContentKind.MEASURE;
                continue;
            }
            if (isStructuralMarker(
                    block, text, rules, "activation_condition", List.of("启动条件", "响应条件", "发布条件"))) {
                kind = ContentKind.CONDITION;
            }
            if (isStructuralMarker(
                    block, text, rules, "response_measure", List.of("响应措施", "应急措施", "处置措施", "协调措施"))) {
                kind = ContentKind.MEASURE;
                if (isStandaloneMarker(text)) {
                    continue;
                }
            }
            if (kind == ContentKind.UNKNOWN) {
                kind = looksLikeCondition(text) ? ContentKind.CONDITION : ContentKind.MEASURE;
            }
            (kind == ContentKind.CONDITION ? conditions : measures).add(block);
        }
        return new CandidateParts(joinText(conditions), joinText(measures), pages(conditions), pages(measures));
    }

    /** 拆分同一段内连续书写的启动条件和响应措施。 */
    private InlineContentParts splitInlineConditionAndMeasure(DocumentBlock block) {
        String text = block.text().trim();
        Matcher matcher = Pattern.compile(
                        "^(.{1,600}?(?:启动|实施|进入|发布).{0,30}(?:响应|预警))[，,。；;](.+)$")
                .matcher(text);
        if (!matcher.matches() || !isExplicitActivationCondition(matcher.group(1))) {
            return null;
        }
        String conditionText = matcher.group(1).trim();
        String measureText = matcher.group(2).trim();
        // “符合三级响应条件时启动三级响应”只是引用其他位置已定义的条件，
        // 不能把这类响应程序说明误当成条件正文。
        if (conditionText.matches(".*符合.{0,20}(?:响应|预警)条件时.*")) {
            return null;
        }
        if (!StringUtils.hasText(measureText)) {
            return null;
        }
        return new InlineContentParts(
                copyBlockWithText(block, conditionText),
                copyBlockWithText(block, measureText));
    }

    /** 创建只替换文本的虚拟文档块，保留来源页和表格属性。 */
    private DocumentBlock copyBlockWithText(DocumentBlock source, String text) {
        return new DocumentBlock(text, source.page(), source.headingLevel(), source.table(), source.cells());
    }

    private ContentKind contextKind(String category, int start, List<DocumentBlock> blocks, SegmentRules rules) {
        DocumentBlock anchorBlock = blocks.get(start);
        String anchor = anchorBlock.text();
        // “5.2.1 一级应急响应”是明确的响应措施入口，应优先于更早的
        // “4.3 应急响应分级”条件上下文，避免跨主章节错误继承内容类型。
        if (!anchorBlock.table()
                && anchorBlock.heading()
                && !anchor.contains("条件")
                && normalize(anchor).matches("^\\d+(?:\\.\\d+){1,4}.*响应.*")) {
            return ContentKind.MEASURE;
        }
        for (int i = start; i >= Math.max(0, start - 12); i--) {
            DocumentBlock block = blocks.get(i);
            boolean condition = isStructuralMarker(
                    block,
                    block.text(),
                    rules,
                    "activation_condition",
                    List.of("启动条件", "响应条件", "发布条件", "响应分级", "预警分级"));
            boolean measure = isStructuralMarker(
                    block,
                    block.text(),
                    rules,
                    "response_measure",
                    List.of("响应措施", "应急措施", "处置措施", "响应行动", "协调措施"));
            if (condition != measure) {
                return condition ? ContentKind.CONDITION : ContentKind.MEASURE;
            }
        }
        if ("WARNING".equals(category) && anchor.contains("预警") && !anchor.contains("响应")) {
            return ContentKind.CONDITION;
        }
        if (anchor.matches(".*(启动|实施).{0,12}(响应|预警响应).*")) {
            return ContentKind.MEASURE;
        }
        return ContentKind.UNKNOWN;
    }

    private boolean isStructuralMarker(
            DocumentBlock block,
            String text,
            SegmentRules rules,
            String code,
            List<String> defaults) {
        // 数据库规则用于补充业务别名，不能覆盖通用默认标记，否则发布一条
        // “启动条件”后会意外丢失“响应分级”等基础识别能力。
        List<String> markers = new ArrayList<>(defaults);
        markers.addAll(rules.markerAliases().getOrDefault(code, List.of()));
        if (markers.stream().noneMatch(text::contains)) {
            return false;
        }
        if (isStandaloneMarker(text) || block.heading()) {
            return true;
        }
        // “在某级基础上，加强以下应急措施：”不是标题块，但冒号明确表示后续内容类型。
        if (text.trim().matches(".*(?:以下)?(?:响应|应急|处置|协调)?措施[:：]$")) {
            return true;
        }
        String value = stripSectionNumber(normalize(text));
        return markers.stream().map(this::normalize).anyMatch(value::startsWith);
    }

    private String stripSectionNumber(String text) {
        return text.replaceFirst("^\\d+(?:\\.\\d+){0,4}[、.]?", "")
                .replaceFirst("^[一二三四五六七八九十]+[、.]", "");
    }

    private boolean isStandaloneMarker(String text) {
        return normalize(text).matches("(启动|响应|发布)?条件|响应措施|应急措施|处置措施|协调措施");
    }

    private boolean looksLikeCondition(String text) {
        return text.matches(".*(发生|预计|达到|符合|出现|超过|低于|造成|可能发生|不能控|无法控).*")
                && !text.matches(".*(组织|开展|调度|派出|加强|负责|协调).*");
    }

    /**
     * 判断正文是否明确表达某级响应/预警的启动关系。
     *
     * <p>仅出现“二级响应不能控制”等跨级条件引用不算独立候选，避免把同一条
     * 一级启动条件同时写入二级结果。</p>
     */
    private boolean isExplicitActivationCondition(String text) {
        return text.matches(".*(发生|预计|达到|符合|出现|超过|低于|造成|可能发生|不能控|无法控).*")
                && text.matches(".*(启动|实施|进入|发布).{0,30}(响应|预警).*");
    }

    private boolean isResponseLifecycleReference(String text) {
        String value = normalize(text);
        return value.contains("响应结束")
                || value.contains("响应终止")
                || value.contains("响应调整")
                || value.contains("响应解除")
                || value.startsWith("结束")
                || value.startsWith("终止");
    }

    private boolean isFlowchartContext(int index, List<DocumentBlock> blocks) {
        DocumentBlock current = blocks.get(index);
        if (current.table()) {
            return false;
        }
        for (int i = index; i >= 0 && blocks.get(i).page() == current.page(); i--) {
            if (normalize(blocks.get(i).text()).contains("流程图")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析“在某级基础上”等继承表达式，并展开为最终有效措施。
     *
     * <p>展开过程带环检测，错误配置不会造成无限递归。</p>
     */
    private void applyWarningInheritance(
            Map<String, MutableLevel> levels,
            List<LevelDefinition> definitions,
            SegmentRules rules) {
        List<String> inheritanceMarkers = rules.markerAliases()
                .getOrDefault("inheritance", List.of("基础上"));
        for (MutableLevel target : levels.values()) {
            String direct = target.directMeasures();
            if (!StringUtils.hasText(direct)) {
                continue;
            }
            for (LevelDefinition source : definitions) {
                if (source.key().equals(target.definition().key())) {
                    continue;
                }
                boolean referenced = source.aliases().stream()
                        .anyMatch(alias -> inheritanceMarkers.stream().anyMatch(marker ->
                                direct.matches("(?s).*" + Pattern.quote(alias)
                                        + ".{0,20}" + Pattern.quote(marker) + ".*")));
                if (referenced) {
                    target.addInherited(source.key());
                }
            }
        }
        for (MutableLevel level : levels.values()) {
            expandMeasures(level, levels, new LinkedHashSet<>());
        }
    }

    private String expandMeasures(MutableLevel level, Map<String, MutableLevel> levels, Set<String> visiting) {
        if (level.effectiveMeasures() != null) {
            return level.effectiveMeasures();
        }
        if (!visiting.add(level.definition().key())) {
            return level.directMeasures();
        }
        List<String> paragraphs = new ArrayList<>();
        for (String sourceKey : level.inheritedFrom()) {
            MutableLevel source = levels.get(sourceKey);
            if (source != null) {
                addParagraphs(paragraphs, expandMeasures(source, levels, visiting));
            }
        }
        addParagraphs(paragraphs, level.directMeasures());
        visiting.remove(level.definition().key());
        String effective = paragraphs.isEmpty() ? null : String.join("\n", paragraphs);
        level.setEffectiveMeasures(effective);
        return effective;
    }

    private void addParagraphs(List<String> target, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        content.lines().map(String::trim).filter(StringUtils::hasText).forEach(line -> {
            if (!target.contains(line)) {
                target.add(line);
            }
        });
    }

    /**
     * 从章节标题、行内列表和 MARKER 入口中提取行动组及职责。
     */
    private List<ActionGroupSegment> extractActionGroups(
            List<DocumentBlock> blocks, SegmentRules rules) {
        Map<String, MutableActionGroup> groups = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            if (isTocBlock(block)) {
                continue;
            }
            String text = block.text().trim();
            Matcher matcher = ACTION_GROUP_PATTERN.matcher(text);
            while (matcher.find()) {
                String name = cleanGroupName(matcher.group(1));
                if (!isUsefulGroupName(name)) {
                    continue;
                }
                String detail = text;
                if (text.length() <= name.length() + 8 && i + 1 < blocks.size()) {
                    detail = text + "\n" + blocks.get(i + 1).text();
                }
                mergeActionGroup(groups, name, detail, block.page(), rules);
            }
            Matcher inline = INLINE_GROUPS_PATTERN.matcher(text);
            if (inline.find()) {
                for (String item : inline.group(1).split("[、，,及和]") ) {
                    String baseName = cleanGroupName(item);
                    String name = baseName.endsWith("组") ? baseName : baseName + "组";
                    if (isUsefulGroupName(name)) {
                        mergeActionGroup(groups, name, text, block.page(), rules);
                    }
                }
            }
        }
        return groups.values().stream().map(MutableActionGroup::toSegment).toList();
    }

    private void mergeActionGroup(
            Map<String, MutableActionGroup> groups,
            String name,
            String detail,
            int page,
            SegmentRules rules) {
        String normalizedName = normalizeGroupName(name);
        MutableActionGroup group = groups.computeIfAbsent(
                normalizedName, ignored -> new MutableActionGroup(actionGroupKey(normalizedName), name));
        group.add(detail, page, rules);
    }

    private String cleanGroupName(String name) {
        return name.replaceFirst("^[（(]?\\d+[）)]", "").trim();
    }

    private boolean isUsefulGroupName(String name) {
        return StringUtils.hasText(name)
                && name.length() >= 3
                && !Set.of("各工作组", "工作组", "领导小组", "指挥部工作组").contains(name);
    }

    private String normalizeGroupName(String name) {
        return name.replaceAll("[\\s:：。；;（）()、，,]", "");
    }

    private String actionGroupKey(String normalizedName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedName.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("action_group_");
            for (int i = 0; i < 8; i++) {
                value.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256。", ex);
        }
    }

    /**
     * 综合标题、表格、目录排除和上下文证据选择最佳章节候选。
     */
    private Optional<SegmentSection> findBestSection(
            String key,
            String level,
            List<String> aliases,
            List<DocumentBlock> blocks,
            List<String> warnings,
            SegmentRules rules) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            String alias = matchingAlias(block.text(), aliases);
            if (alias == null) {
                continue;
            }
            if (isTocBlock(block)) {
                candidates.add(new Candidate(i, alias, matchType(block, alias, aliases, rules), true));
                continue;
            }
            candidates.add(new Candidate(i, alias, matchType(block, alias, aliases, rules), false));
        }

        List<Candidate> bodyCandidates = candidates.stream()
                .filter(candidate -> !candidate.toc())
                .sorted(Comparator.comparingInt(candidate -> score(candidate, blocks.get(candidate.index()))))
                .toList();
        long structuralCandidateCount = bodyCandidates.stream()
                .filter(candidate -> candidate.matchedBy() != MatchedBy.CONTEXT_FALLBACK)
                .count();
        if (structuralCandidateCount > 1) {
            warnings.add((level == null ? "指挥体系" : level) + "存在多个候选章节，已选择正文中优先级最高的候选。");
        }
        if (!bodyCandidates.isEmpty()) {
            return Optional.of(toSection(key, level, bodyCandidates.get(0), blocks, rules));
        }
        if (!candidates.isEmpty()) {
            warnings.add((level == null ? "指挥体系" : level) + "仅在疑似目录中命中，未提取正文内容。");
        } else {
            Optional<SegmentSection> fallback = contextFallback(key, level, aliases, blocks);
            fallback.ifPresent(section -> warnings.add((level == null ? "指挥体系" : level)
                    + "未发现明确标题，已按上下文提取。"));
            return fallback;
        }
        return Optional.empty();
    }

    private SegmentSection toSection(
            String key, String level, Candidate candidate, List<DocumentBlock> blocks, SegmentRules rules) {
        DocumentBlock anchor = blocks.get(candidate.index());
        if (anchor.table()) {
            return tableSection(key, level, candidate, blocks, rules);
        }

        int end = findSectionEnd(candidate.index(), blocks, level != null, rules);
        List<DocumentBlock> contentBlocks = blocks.subList(candidate.index() + 1, end);
        String content = joinText(contentBlocks);
        if (!StringUtils.hasText(content)) {
            content = anchor.text();
            contentBlocks = List.of(anchor);
        }
        return new SegmentSection(
                key,
                level,
                anchor.text(),
                content,
                pages(contentBlocks),
                candidate.matchedBy(),
                List.of(anchor.text()));
    }

    private SegmentSection tableSection(
            String key, String level, Candidate candidate, List<DocumentBlock> blocks, SegmentRules rules) {
        DocumentBlock anchor = blocks.get(candidate.index());
        List<DocumentBlock> rows = new ArrayList<>();
        rows.add(anchor);
        for (int i = candidate.index() + 1; i < Math.min(candidate.index() + 3, blocks.size()); i++) {
            DocumentBlock next = blocks.get(i);
            if (!next.table() || containsAnyResponseAlias(next.text(), rules)) {
                break;
            }
            rows.add(next);
        }
        return new SegmentSection(
                key,
                level,
                anchor.text(),
                joinText(rows),
                pages(rows),
                MatchedBy.TABLE_ROW,
                List.of(anchor.text()));
    }

    private Optional<SegmentSection> contextFallback(
            String key, String level, List<String> aliases, List<DocumentBlock> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            String alias = matchingAlias(block.text(), aliases);
            if (alias == null || isTocBlock(block) || block.text().length() < 30) {
                continue;
            }
            int end = Math.min(blocks.size(), i + 4);
            List<DocumentBlock> contentBlocks = blocks.subList(i, end);
            return Optional.of(new SegmentSection(
                    key,
                    level,
                    alias,
                    joinText(contentBlocks),
                    pages(contentBlocks),
                    MatchedBy.CONTEXT_FALLBACK,
                    List.of(block.text())));
        }
        return Optional.empty();
    }

    private int findSectionEnd(
            int start, List<DocumentBlock> blocks, boolean responseSection, SegmentRules rules) {
        DocumentBlock anchor = blocks.get(start);
        int currentLevel = anchor.headingLevel() > 0
                ? anchor.headingLevel()
                : inferHeadingLevel(anchor.text(), rules);
        if (currentLevel <= 0) {
            currentLevel = 2;
        }
        for (int i = start + 1; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            if (responseSection && isResponseBoundary(block.text(), rules)) {
                return i;
            }
            int level = block.headingLevel() > 0
                    ? block.headingLevel()
                    : inferHeadingLevel(block.text(), rules);
            if (level > 0 && level <= currentLevel) {
                return i;
            }
        }
        return blocks.size();
    }

    private MatchedBy matchType(
            DocumentBlock block, String alias, List<String> aliases, SegmentRules rules) {
        if (block.table()) {
            return MatchedBy.TABLE_ROW;
        }
        boolean heading = block.heading() || inferHeadingLevel(block.text(), rules) > 0;
        if (!heading) {
            return MatchedBy.CONTEXT_FALLBACK;
        }
        if (normalize(block.text()).contains(normalize(aliases.get(0)))) {
            return MatchedBy.HEADING;
        }
        if (startsWithNumber(block.text())) {
            return MatchedBy.NUMBERED_SECTION;
        }
        return MatchedBy.HEADING_ALIAS;
    }

    private int score(Candidate candidate, DocumentBlock block) {
        int score = 0;
        if (candidate.matchedBy() == MatchedBy.HEADING || candidate.matchedBy() == MatchedBy.HEADING_ALIAS) {
            score += 1;
        } else if (candidate.matchedBy() == MatchedBy.TABLE_ROW) {
            score += 2;
        } else if (candidate.matchedBy() == MatchedBy.NUMBERED_SECTION) {
            score += 3;
        } else {
            score += 10;
        }
        score += Math.min(block.text().length() / 20, 5);
        return score;
    }

    private String matchingAlias(String text, List<String> aliases) {
        String normalizedText = normalize(text);
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if ("重大响应".equals(alias) && normalizedText.contains(normalize("特别重大响应"))) {
                continue;
            }
            if (containsAlias(normalizedText, normalizedAlias)) {
                return alias;
            }
        }
        return null;
    }

    private boolean containsAlias(String normalizedText, String normalizedAlias) {
        if (normalizedAlias.matches("(?:I{1,3}|IV)级(?:应急)?响应")) {
            return Pattern.compile("(^|[^IV])" + Pattern.quote(normalizedAlias) + "($|[^IV])")
                    .matcher(normalizedText)
                    .find();
        }
        return normalizedText.contains(normalizedAlias);
    }

    private boolean containsAnyResponseAlias(String text, SegmentRules rules) {
        return rules.responseAliases().values().stream()
                .flatMap(List::stream)
                .anyMatch(alias -> containsAlias(normalize(text), normalize(alias)));
    }

    private boolean isResponseBoundary(String text, SegmentRules rules) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (isStandaloneResponseHeading(text, rules)) {
            return true;
        }
        String normalized = normalize(text);
        return rules.responseTailHeadings().stream()
                .map(this::normalize)
                .anyMatch(normalized::contains);
    }

    private boolean isStandaloneResponseHeading(String text, SegmentRules rules) {
        String normalized = normalize(text);
        if (normalized.length() > 16) {
            return false;
        }
        return rules.responseAliases().values().stream()
                .flatMap(List::stream)
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    private boolean isTocBlock(DocumentBlock block) {
        String text = normalize(block.text());
        return text.equals("目录")
                || text.contains("目录")
                || Pattern.compile("\\.{3,}\\d*$").matcher(block.text()).find()
                || Pattern.compile(".+\\s+\\d{1,3}$").matcher(block.text()).find() && block.page() <= 3;
    }

    private boolean startsWithNumber(String text) {
        return text.matches("^(第[一二三四五六七八九十]+[章节篇]|[一二三四五六七八九十]+[、.]|\\d+(?:\\.\\d+){0,3}[、.\\s]).*");
    }

    private int inferHeadingLevel(String text, SegmentRules rules) {
        if (!StringUtils.hasText(text) || text.length() > 80) {
            return 0;
        }
        if (isStandaloneResponseHeading(text, rules)) {
            return 3;
        }
        if (rules.responseTailHeadings().stream().map(this::normalize).anyMatch(normalize(text)::contains)) {
            return 2;
        }
        if (text.matches("^第[一二三四五六七八九十]+[章节篇].*")) {
            return 1;
        }
        if (text.matches("^[一二三四五六七八九十]+[、.].*")) {
            return 2;
        }
        if (text.matches("^\\d+(?:\\.\\d+){0,3}[、.\\s].*")) {
            return text.contains(".") ? 3 : 2;
        }
        return 0;
    }

    private String joinText(List<DocumentBlock> blocks) {
        return blocks.stream()
                .map(DocumentBlock::text)
                .filter(StringUtils::hasText)
                .distinct()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private List<Integer> pages(List<DocumentBlock> blocks) {
        Set<Integer> pages = new LinkedHashSet<>();
        for (DocumentBlock block : blocks) {
            if (block.page() > 0) {
                pages.add(block.page());
            }
        }
        return new ArrayList<>(pages);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s　:：；;（）()\\[\\]【】]", "")
                .replace("Ｉ", "I")
                .replace("Ⅱ", "II")
                .replace("Ⅲ", "III")
                .replace("Ⅳ", "IV")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private record LevelDefinition(
            String key, String level, String colorKey, String colorName, List<String> aliases) {
    }

    private record CandidateParts(
            String conditions,
            String measures,
            List<Integer> conditionPages,
            List<Integer> measurePages) {
    }

    /** 同一原始段落拆出的条件块和措施块。 */
    private record InlineContentParts(DocumentBlock condition, DocumentBlock measure) {
    }

    private enum ContentKind {
        UNKNOWN,
        CONDITION,
        MEASURE
    }

    private final class MutableLevel {

        private final LevelDefinition definition;
        private final List<String> conditions = new ArrayList<>();
        private final List<String> measures = new ArrayList<>();
        private final Set<Integer> conditionPages = new LinkedHashSet<>();
        private final Set<Integer> measurePages = new LinkedHashSet<>();
        private final List<String> evidence = new ArrayList<>();
        private final List<String> inheritedFrom = new ArrayList<>();
        private MatchedBy matchedBy = MatchedBy.CONTEXT_FALLBACK;
        private String title;
        private String effectiveMeasures;

        private MutableLevel(LevelDefinition definition) {
            this.definition = definition;
        }

        private void add(DocumentBlock anchor, CandidateParts parts, String resolvedTitle) {
            if (title == null) {
                title = resolvedTitle;
            }
            if (evidence.size() < 5 && !evidence.contains(anchor.text())) {
                evidence.add(anchor.text());
            }
            if (anchor.table()) {
                matchedBy = MatchedBy.TABLE_ROW;
            } else if (anchor.heading()) {
                matchedBy = MatchedBy.HEADING_ALIAS;
            }
            addParagraphs(conditions, parts.conditions());
            addParagraphs(measures, parts.measures());
            conditionPages.addAll(parts.conditionPages());
            measurePages.addAll(parts.measurePages());
        }

        /** 将包含级别名称的条件正文直接合并到对应级别。 */
        private void addCondition(DocumentBlock block) {
            if (title == null) {
                title = definition.level();
            }
            addParagraphs(conditions, block.text());
            if (block.page() > 0) {
                conditionPages.add(block.page());
            }
            if (evidence.size() < 5 && !evidence.contains(block.text())) {
                evidence.add(block.text());
            }
        }

        private LevelDefinition definition() {
            return definition;
        }

        private String directMeasures() {
            return measures.isEmpty() ? null : String.join("\n", measures);
        }

        private String effectiveMeasures() {
            return effectiveMeasures;
        }

        private void setEffectiveMeasures(String effectiveMeasures) {
            this.effectiveMeasures = effectiveMeasures;
        }

        private List<String> inheritedFrom() {
            return inheritedFrom;
        }

        private void addInherited(String key) {
            if (!inheritedFrom.contains(key)) {
                inheritedFrom.add(key);
            }
        }

        private ResponseLevelSegment toSegment() {
            String activationConditions = conditions.isEmpty() ? null : String.join("\n", conditions);
            String direct = directMeasures();
            String effective = effectiveMeasures != null ? effectiveMeasures : direct;
            String status;
            if (!StringUtils.hasText(activationConditions) && !StringUtils.hasText(effective)) {
                status = "MISSING";
            } else if (!StringUtils.hasText(activationConditions) || !StringUtils.hasText(effective)) {
                status = "PARTIAL";
            } else {
                status = "EXTRACTED";
            }
            return new ResponseLevelSegment(
                    definition.key(),
                    definition.key().startsWith("warning_") ? "WARNING" : "EMERGENCY",
                    definition.level(),
                    title == null ? definition.level() : title,
                    definition.colorKey(),
                    definition.colorName(),
                    status,
                    activationConditions,
                    direct,
                    effective,
                    List.copyOf(inheritedFrom),
                    List.copyOf(conditionPages),
                    List.copyOf(measurePages),
                    matchedBy,
                    List.copyOf(evidence));
        }
    }

    private final class MutableActionGroup {

        private final String key;
        private final String name;
        private final List<String> leads = new ArrayList<>();
        private final List<String> members = new ArrayList<>();
        private final List<String> responsibilities = new ArrayList<>();
        private final List<String> raw = new ArrayList<>();
        private final Set<Integer> pages = new LinkedHashSet<>();

        private MutableActionGroup(String key, String name) {
            this.key = key;
            this.name = name;
        }

        private void add(String detail, int page, SegmentRules rules) {
            addParagraphs(raw, detail);
            if (page > 0) {
                pages.add(page);
            }
            List<String> leadMarkers = rules.markerAliases()
                    .getOrDefault("group_lead", List.of("牵头", "负责"));
            Pattern leadPattern = Pattern.compile(
                    "由(.{1,100}?)(?:" + quotedAlternatives(leadMarkers) + ")");
            Matcher lead = leadPattern.matcher(detail);
            while (lead.find()) {
                addOrganizations(leads, lead.group(1));
            }
            List<String> memberMarkers = rules.markerAliases()
                    .getOrDefault("group_member", List.of("组成", "参加", "为成员"));
            Pattern memberPattern = Pattern.compile(
                    "(?:联合|会同|成员包括|牵头[，,])(.{1,160}?)(?:"
                            + quotedAlternatives(memberMarkers) + ")");
            Matcher member = memberPattern.matcher(detail);
            while (member.find()) {
                addOrganizations(members, member.group(1));
            }
            List<String> responsibilityMarkers = new ArrayList<>(rules.markerAliases()
                    .getOrDefault("group_responsibility", List.of("主要负责", "负责")));
            if (!responsibilityMarkers.contains("负责")) {
                responsibilityMarkers.add("负责");
            }
            int responsibilityIndex = responsibilityMarkers.stream()
                    .mapToInt(detail::lastIndexOf)
                    .max()
                    .orElse(-1);
            if (responsibilityIndex >= 0) {
                addResponsibility(detail.substring(responsibilityIndex));
            } else {
                // 有些预案不写“主要负责”，而是“抢救抢险组。组织……；协调……”。
                // 组名后的动作描述同样是职责，但纯粹的牵头/组成单位说明不在此兜底。
                int nameIndex = detail.indexOf(name);
                if (nameIndex >= 0) {
                    String value = detail.substring(nameIndex + name.length())
                            .replaceFirst("^[\\s\\r\\n:：。；;、，,]+", "")
                            .trim();
                    boolean actionDescription = value.matches(
                            ".*(组织|承担|协调|指导|调配|搜救|保障|开展|监测|发布|维护|恢复|制定|实施|收集|汇总|处置|抢修|安置|供应).*" );
                    boolean organizationOnly = value.matches(
                            "^(由|牵头单位|成员单位).{0,200}(牵头|组成|参加|为成员)[。；;]?$" );
                    if (actionDescription && !organizationOnly) {
                        addResponsibility(value);
                    }
                }
            }
        }

        /** 去重保存非空职责文本。 */
        private void addResponsibility(String value) {
            String normalized = value == null ? "" : value.trim();
            if (StringUtils.hasText(normalized) && !responsibilities.contains(normalized)) {
                responsibilities.add(normalized);
            }
        }

        private void addOrganizations(List<String> target, String text) {
            for (String item : text.split("[、，,及和]") ) {
                String value = item.replaceAll("^(由|联合|会同)", "").trim();
                if (StringUtils.hasText(value) && value.length() <= 60 && !target.contains(value)) {
                    target.add(value);
                }
            }
        }

        private ActionGroupSegment toSegment() {
            return new ActionGroupSegment(
                    key,
                    name,
                    List.copyOf(leads),
                    List.copyOf(members),
                    responsibilities.isEmpty() ? null : String.join("\n", responsibilities),
                    raw.isEmpty() ? null : String.join("\n", raw),
                    List.copyOf(pages),
                    MatchedBy.CONTEXT_FALLBACK,
                    raw.stream().limit(5).toList());
        }
    }

    private String quotedAlternatives(List<String> values) {
        String value = values.stream()
                .filter(StringUtils::hasText)
                .map(Pattern::quote)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        return StringUtils.hasText(value) ? value : "(?!)";
    }

    private record Candidate(int index, String alias, MatchedBy matchedBy, boolean toc) {
    }
}
