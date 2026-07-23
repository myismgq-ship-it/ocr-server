package com.gsafety.ocrtool.extraction;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.OcrTemplateProperties;
import com.gsafety.ocrtool.recognition.OcrLine;
import com.gsafety.ocrtool.management.ManagedTemplateProvider;
import com.gsafety.ocrtool.recognition.OcrPoint;
import com.gsafety.ocrtool.recognition.OcrRecognitionService;
import com.gsafety.ocrtool.recognition.OcrResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 基于 OCR 坐标和模板标签的通用字段抽取服务。
 *
 * <p>优先使用数据库已发布模板，静态 YAML 作为兼容回退；
 * 许可证专用纠错通过独立处理器启用，不污染通用抽取逻辑。</p>
 */
@Service
public class OcrExtractService {

    private static final double SAME_ROW_RATIO = 0.75;
    private static final int RIGHT_EDGE_PADDING = 20;
    private static final int WRAPPED_X_TOLERANCE = 120;
    private static final int LABEL_COLUMN_TOLERANCE = 220;
    private static final int SAME_ROW_MAX_GAP = 320;
    private static final int LICENSE_NUMBER_VERTICAL_TOLERANCE = 80;

    /** 通用 OCR 识别入口。 */
    private final OcrRecognitionService ocrRecognitionService;
    /** 静态 YAML 模板集合。 */
    private final OcrTemplateProperties templateProperties;
    /** 安全生产许可证专用后处理器。 */
    private final SafetyLicenseTemplateProcessor safetyLicenseTemplateProcessor;

    /** 数据库已发布动态模板；兼容单元测试时允许为空。 */
    private final ManagedTemplateProvider managedTemplateProvider;

    public OcrExtractService(
            OcrRecognitionService ocrRecognitionService,
            OcrTemplateProperties templateProperties) {
        this(
                ocrRecognitionService,
                templateProperties,
                new SafetyLicenseTemplateProcessor(),
                null);
    }

    public OcrExtractService(
            OcrRecognitionService ocrRecognitionService,
            OcrTemplateProperties templateProperties,
            SafetyLicenseTemplateProcessor safetyLicenseTemplateProcessor) {
        this(ocrRecognitionService, templateProperties, safetyLicenseTemplateProcessor, null);
    }

    @Autowired
    public OcrExtractService(
            OcrRecognitionService ocrRecognitionService,
            OcrTemplateProperties templateProperties,
            SafetyLicenseTemplateProcessor safetyLicenseTemplateProcessor,
            ManagedTemplateProvider managedTemplateProvider) {
        this.ocrRecognitionService = ocrRecognitionService;
        this.templateProperties = templateProperties;
        this.safetyLicenseTemplateProcessor = safetyLicenseTemplateProcessor;
        this.managedTemplateProvider = managedTemplateProvider;
    }

    /**
     * 合并静态模板和数据库已发布模板编码。
     */
    public Set<String> templateCodes() {
        Set<String> codes = new LinkedHashSet<>();
        Map<String, OcrTemplateProperties.Template> templates = templateProperties.getTemplates();
        if (templates != null) {
            codes.addAll(templates.keySet());
        }
        if (managedTemplateProvider != null) {
            codes.addAll(managedTemplateProvider.publishedCodes());
        }
        return Set.copyOf(codes);
    }

    /**
     * 使用指定模板编码抽取上传图片字段。
     */
    public OcrExtractResult extract(String templateCode, MultipartFile file) {
        return extractWithTemplate(templateCode, findTemplate(templateCode), file);
    }

    /**
     * 使用调用方提供的模板对象抽取字段，供草稿模板隔离测试。
     *
     * <p>每个字段都会返回状态、命中标签、来源文本和坐标。</p>
     */
    public OcrExtractResult extractWithTemplate(
            String templateCode,
            OcrTemplateProperties.Template template,
            MultipartFile file) {
        OcrResult ocrResult = ocrRecognitionService.recognizeLegacy(file);
        List<LayoutText> texts = toLayoutTexts(ocrResult.lines());
        Set<String> allLabels = allLabels(template);

        Map<String, OcrExtractField> fields = new LinkedHashMap<>();
        List<String> lowConfidenceFields = new ArrayList<>();
        List<String> missingFields = new ArrayList<>();
        List<String> invalidFields = new ArrayList<>();
        boolean safetyLicenseProcessor = "safety_license".equalsIgnoreCase(templateCode)
                || "safety-license".equalsIgnoreCase(template.getProcessor());
        // 专用处理器只由模板编码或 processor 显式启用，其他证照始终走通用逻辑。
        Map<String, OcrTemplateProperties.Field> templateFields =
                template.getFields() == null ? Map.of() : template.getFields();

        for (Map.Entry<String, OcrTemplateProperties.Field> entry : templateFields.entrySet()) {
            String fieldName = entry.getKey();
            OcrExtractField value = extractField(texts, entry.getValue(), allLabels);
            if (safetyLicenseProcessor && !StringUtils.hasText(value.value())) {
                value = fallbackField(fieldName, texts, ocrResult.text(), value);
            }
            if (safetyLicenseProcessor) {
                value = safetyLicenseTemplateProcessor.process(fieldName, value);
            }
            value = classifyField(value, entry.getValue());
            fields.put(fieldName, value);
            switch (value.status()) {
                case LOW_CONFIDENCE -> lowConfidenceFields.add(fieldName);
                case MISSING -> missingFields.add(fieldName);
                case INVALID -> invalidFields.add(fieldName);
                default -> {
                }
            }
        }

        return new OcrExtractResult(
                templateCode,
                Map.copyOf(fields),
                List.copyOf(lowConfidenceFields),
                List.copyOf(missingFields),
                List.copyOf(invalidFields),
                ocrResult.text(),
                ocrResult.lines());
    }

    /**
     * 查找模板时数据库发布版本优先，未发布或不存在时回退静态 YAML。
     *
     * <p>这样既支持在线发布，也保持原有配置部署方式向后兼容。</p>
     */
    private OcrTemplateProperties.Template findTemplate(String templateCode) {
        if (!StringUtils.hasText(templateCode)) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TEMPLATE_CODE_REQUIRED", "OCR 模板编码不能为空。");
        }
        Map<String, OcrTemplateProperties.Template> templates = templateProperties.getTemplates();
        if (managedTemplateProvider != null) {
            OcrTemplateProperties.Template managed =
                    managedTemplateProvider.findPublished(templateCode).orElse(null);
            if (managed != null) {
                return managed;
            }
        }
        if (templates == null || templates.isEmpty()) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TEMPLATE_NOT_CONFIGURED", "OCR 模板未配置。");
        }
        OcrTemplateProperties.Template template = templates.get(templateCode);
        if (template == null) {
            throw new OcrException(HttpStatus.BAD_REQUEST, "TEMPLATE_NOT_FOUND", "OCR 模板不存在：" + templateCode);
        }
        return template;
    }

    /**
     * 先定位标签，再根据 right/below 方向和停止标签收集字段值。
     */
    private OcrExtractField extractField(
            List<LayoutText> texts,
            OcrTemplateProperties.Field field,
            Set<String> allLabels) {
        if (field == null) {
            return emptyField();
        }

        LabelHit label = findLabel(texts, field.getLabels());
        if (label == null) {
            return emptyField();
        }

        Set<String> stopLabels = stopLabels(field, allLabels);
        List<LayoutText> values = new ArrayList<>();
        boolean stopped = false;
        if (StringUtils.hasText(label.inlineValue())) {
            stopped = !addValue(values, label.anchor().withText(label.inlineValue()), stopLabels, field);
            if (!stopped && "right".equalsIgnoreCase(field.getDirection())) {
                stopped = collectRight(texts, label.anchor(), values, stopLabels, field);
            }
        } else if ("below".equalsIgnoreCase(field.getDirection())) {
            stopped = collectBelow(texts, label.anchor(), values, stopLabels, field);
        } else {
            stopped = collectRight(texts, label.anchor(), values, stopLabels, field);
        }

        if (field.isMergeWrappedLines() && !stopped) {
            collectWrapped(texts, label.anchor(), values, stopLabels, field);
        }
        return toField(values, label.matchedLabel());
    }

    private LabelHit findLabel(List<LayoutText> texts, List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        for (LayoutText text : texts) {
            String normalizedText = normalize(text.text());
            for (String label : labels) {
                for (String candidate : labelCandidates(label)) {
                    String normalizedLabel = normalize(candidate);
                    if (!StringUtils.hasText(normalizedLabel)) {
                        continue;
                    }
                    int index = normalizedText.indexOf(normalizedLabel);
                    if (index >= 0) {
                        return new LabelHit(text, normalizedText.substring(index + normalizedLabel.length()), candidate);
                    }
                }
            }
        }
        return null;
    }

    private boolean collectRight(
            List<LayoutText> texts,
            LayoutText anchor,
            List<LayoutText> values,
            Set<String> stopLabels,
            OcrTemplateProperties.Field field) {
        return texts.stream()
                .filter(text -> text != anchor)
                .filter(text -> isSameRow(text, anchor))
                .filter(text -> text.xMin() >= anchor.xMax() - RIGHT_EDGE_PADDING)
                .sorted(Comparator.comparingDouble(LayoutText::xMin))
                .anyMatch(new SameRowValueCollector(anchor, values, stopLabels, field)::add);
    }

    private boolean collectBelow(
            List<LayoutText> texts,
            LayoutText anchor,
            List<LayoutText> values,
            Set<String> stopLabels,
            OcrTemplateProperties.Field field) {
        double bottom = anchor.yMax();
        for (LayoutText text : texts) {
            if (text == anchor || text.yMin() <= anchor.yMin()) {
                continue;
            }
            if (isKnownLabel(text, stopLabels) || text.yMin() - bottom > maxLineGap(anchor)) {
                return true;
            }
            if (!addValue(values, text, stopLabels, field)) {
                return true;
            }
            bottom = Math.max(bottom, text.yMax());
        }
        return false;
    }

    private void collectWrapped(
            List<LayoutText> texts,
            LayoutText anchor,
            List<LayoutText> values,
            Set<String> stopLabels,
            OcrTemplateProperties.Field field) {
        double valueStartX = values.stream()
                .mapToDouble(LayoutText::xMin)
                .min()
                .orElse(anchor.xMax());
        double bottom = values.stream()
                .mapToDouble(LayoutText::yMax)
                .max()
                .orElse(anchor.yMax());

        for (LayoutText text : texts) {
            if (text == anchor || values.contains(text) || text.yMin() <= anchor.yMin()) {
                continue;
            }
            if (isKnownLabel(text, stopLabels)) {
                if (text.xMin() >= valueStartX - RIGHT_EDGE_PADDING
                        || valueStartX - text.xMax() <= LABEL_COLUMN_TOLERANCE) {
                    break;
                }
                continue;
            }
            if (text.yMin() - bottom > maxLineGap(anchor)) {
                break;
            }
            if (text.xMin() >= valueStartX - RIGHT_EDGE_PADDING
                    && Math.abs(text.xMin() - valueStartX) <= WRAPPED_X_TOLERANCE) {
                if (!addValue(values, text, stopLabels, field)) {
                    break;
                }
                bottom = Math.max(bottom, text.yMax());
            }
        }
    }

    private boolean addValue(
            List<LayoutText> values,
            LayoutText text,
            Set<String> stopLabels,
            OcrTemplateProperties.Field field) {
        if (exceedsMaxLines(values, text, field)) {
            return false;
        }
        int stopIndex = firstStopLabelIndex(text.text(), stopLabels);
        String value = stopIndex < 0 ? text.text() : prefixByNormalizedLength(text.text(), stopIndex);
        if (StringUtils.hasText(value)) {
            values.add(text.withText(value.trim()));
        }
        return stopIndex < 0;
    }

    private boolean exceedsMaxLines(
            List<LayoutText> values,
            LayoutText candidate,
            OcrTemplateProperties.Field field) {
        if (field.getMaxLines() <= 0 || values.isEmpty()) {
            return false;
        }
        boolean sameExistingRow = values.stream().anyMatch(value -> isSameRow(value, candidate));
        if (sameExistingRow) {
            return false;
        }
        List<LayoutText> rows = new ArrayList<>();
        for (LayoutText value : values) {
            boolean knownRow = rows.stream().anyMatch(row -> isSameRow(row, value));
            if (!knownRow) {
                rows.add(value);
            }
        }
        return rows.size() >= field.getMaxLines();
    }

    private int firstStopLabelIndex(String text, Set<String> stopLabels) {
        String normalizedText = normalize(text);
        int result = -1;
        for (String label : stopLabels) {
            int index = normalizedText.indexOf(label);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    private String prefixByNormalizedLength(String text, int normalizedLength) {
        int count = 0;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!isIgnoredSeparator(ch)) {
                if (count >= normalizedLength) {
                    break;
                }
                count++;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private OcrExtractField toField(List<LayoutText> values) {
        return toField(values, null);
    }

    private OcrExtractField toField(List<LayoutText> values, String matchedLabel) {
        if (values.isEmpty()) {
            return emptyField();
        }
        List<String> sourceText = values.stream()
                .map(LayoutText::text)
                .filter(StringUtils::hasText)
                .toList();
        String value = cleanValue(sourceText);
        double confidence = values.stream()
                .map(LayoutText::confidence)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        List<List<OcrPoint>> sourceBoxes = values.stream()
                .map(LayoutText::box)
                .toList();
        return new OcrExtractField(
                value,
                confidence,
                sourceText,
                OcrExtractStatus.EXTRACTED,
                matchedLabel,
                sourceBoxes);
    }

    private OcrExtractField emptyField() {
        return new OcrExtractField(null, null, List.of());
    }

    /**
     * 按“缺失→格式无效→低置信度→成功”的确定顺序分类字段状态。
     *
     * <p>状态互斥，调用方无需再从 lowConfidenceFields 推断字段是否缺失。</p>
     */
    private OcrExtractField classifyField(
            OcrExtractField value,
            OcrTemplateProperties.Field field) {
        OcrExtractStatus status;
        if (!StringUtils.hasText(value.value())) {
            status = OcrExtractStatus.MISSING;
        } else if (field != null
                && StringUtils.hasText(field.getPattern())
                && !Pattern.matches(field.getPattern(), value.value())) {
            status = OcrExtractStatus.INVALID;
        } else if (field == null
                || value.confidence() == null
                || value.confidence() < field.getMinConfidence()) {
            status = OcrExtractStatus.LOW_CONFIDENCE;
        } else {
            status = OcrExtractStatus.EXTRACTED;
        }
        return new OcrExtractField(
                value.value(),
                value.confidence(),
                value.sourceText(),
                status,
                value.matchedLabel(),
                value.sourceBoxes());
    }

    private List<LayoutText> toLayoutTexts(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<LayoutText> texts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            if (StringUtils.hasText(line.text())) {
                texts.add(LayoutText.from(line, i));
            }
        }
        texts.sort(Comparator
                .comparingDouble(LayoutText::yMin)
                .thenComparingDouble(LayoutText::xMin));
        return texts;
    }

    private Set<String> allLabels(OcrTemplateProperties.Template template) {
        Set<String> labels = new LinkedHashSet<>();
        Map<String, OcrTemplateProperties.Field> fields =
                template.getFields() == null ? Map.of() : template.getFields();
        for (OcrTemplateProperties.Field field : fields.values()) {
            if (field == null || field.getLabels() == null) {
                continue;
            }
            for (String label : field.getLabels()) {
                String normalized = normalize(label);
                if (StringUtils.hasText(normalized)) {
                    labels.add(normalized);
                }
                labels.addAll(labelCandidates(label).stream()
                        .map(this::normalize)
                        .filter(StringUtils::hasText)
                        .toList());
            }
        }
        return labels;
    }

    private Set<String> stopLabels(OcrTemplateProperties.Field field, Set<String> allLabels) {
        Set<String> labels = new LinkedHashSet<>(allLabels);
        if (field.getStopLabels() != null) {
            for (String label : field.getStopLabels()) {
                String normalized = normalize(label);
                if (StringUtils.hasText(normalized)) {
                    labels.add(normalized);
                }
                labels.addAll(labelCandidates(label).stream()
                        .map(this::normalize)
                        .filter(StringUtils::hasText)
                        .toList());
            }
        }
        if (field.getLabels() != null) {
            field.getLabels().forEach(label -> labels.remove(normalize(label)));
        }
        return labels;
    }

    private boolean isKnownLabel(LayoutText text, Set<String> labels) {
        String normalized = normalize(text.text());
        return labels.stream().anyMatch(normalized::contains);
    }

    private boolean isSameRow(LayoutText text, LayoutText anchor) {
        double maxHeight = Math.max(text.height(), anchor.height());
        return Math.abs(text.centerY() - anchor.centerY()) <= maxHeight * SAME_ROW_RATIO;
    }

    private double maxLineGap(LayoutText anchor) {
        return Math.max(anchor.height() * 2.5, 80);
    }

    private String cleanValue(List<String> sourceText) {
        String value = String.join("", sourceText)
                .replaceAll("\\s+", "")
                .replaceAll("^[：:]+", "");
        return StringUtils.hasText(value) ? value : null;
    }

    private OcrExtractField fallbackField(
            String fieldName,
            List<LayoutText> texts,
            String rawText,
            OcrExtractField original) {
        if ("licenseNumber".equals(fieldName)) {
            return fallbackLicenseNumber(texts, rawText, original);
        }
        if (!"enterpriseName".equals(fieldName)) {
            return original;
        }
        LayoutText licenseNumber = findLabel(texts, List.of("编号", "许可证编号", "证书编号")) == null
                ? null
                : findLabel(texts, List.of("编号", "许可证编号", "证书编号")).anchor();
        LayoutText principal = findLabel(texts, List.of("主要负责人", "法定代表人", "负责人")) == null
                ? null
                : findLabel(texts, List.of("主要负责人", "法定代表人", "负责人")).anchor();
        if (licenseNumber == null || principal == null) {
            return original;
        }
        List<LayoutText> candidates = texts.stream()
                .filter(text -> text.yMin() > licenseNumber.yMax())
                .filter(text -> text.yMax() < principal.yMin() + principal.height())
                .filter(text -> text.xMin() < principal.xMax() + 500)
                .filter(text -> !isKnownLabel(text, Set.of("编号", "主要负责人", "单位地址", "经济类型", "有效期", "发证机关", "发证日期")))
                .toList();
        return candidates.isEmpty() ? original : toField(candidates);
    }

    private OcrExtractField fallbackLicenseNumber(List<LayoutText> texts, String rawText, OcrExtractField original) {
        String originalValue = safetyLicenseTemplateProcessor.extractLicenseNumber(original.value());
        if (StringUtils.hasText(originalValue)) {
            return new OcrExtractField(
                    originalValue,
                    original.confidence(),
                    original.sourceText(),
                    original.status(),
                    original.matchedLabel(),
                    original.sourceBoxes());
        }

        String rawTextValue = safetyLicenseTemplateProcessor.extractLicenseNumber(rawText);
        if (StringUtils.hasText(rawTextValue)) {
            return new OcrExtractField(rawTextValue, original.confidence(), List.of(rawTextValue));
        }

        OcrExtractField nearLabel = fallbackLicenseNumberNearLabel(texts);
        if (StringUtils.hasText(nearLabel.value())) {
            return nearLabel;
        }

        String allText = texts.stream()
                .map(LayoutText::text)
                .reduce("", String::concat);
        String value = safetyLicenseTemplateProcessor.extractLicenseNumber(allText);
        if (!StringUtils.hasText(value)) {
            return original;
        }

        List<LayoutText> candidates = texts.stream()
                .filter(text -> isLicenseNumberSource(text, value))
                .toList();
        if (candidates.isEmpty()) {
            return new OcrExtractField(value, original.confidence(), List.of(value));
        }
        double confidence = candidates.stream()
                .map(LayoutText::confidence)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        List<String> sourceText = candidates.stream()
                .map(LayoutText::text)
                .filter(StringUtils::hasText)
                .toList();
        return new OcrExtractField(
                value,
                confidence,
                sourceText,
                OcrExtractStatus.EXTRACTED,
                "编号",
                candidates.stream().map(LayoutText::box).toList());
    }

    private OcrExtractField fallbackLicenseNumberNearLabel(List<LayoutText> texts) {
        LabelHit label = findLabel(texts, List.of("编号", "许可证编号", "证书编号"));
        if (label == null) {
            return emptyField();
        }
        LayoutText anchor = label.anchor();
        List<LayoutText> candidates = texts.stream()
                .filter(text -> text != anchor)
                .filter(text -> text.xMin() >= anchor.xMax() - RIGHT_EDGE_PADDING)
                .filter(text -> Math.abs(text.centerY() - anchor.centerY()) <= LICENSE_NUMBER_VERTICAL_TOLERANCE)
                .filter(text -> text.xMin() - anchor.xMax() <= SAME_ROW_MAX_GAP * 2.0)
                .sorted(Comparator.comparingDouble(LayoutText::xMin))
                .toList();
        String value = safetyLicenseTemplateProcessor.extractLicenseNumber(candidates.stream()
                .map(LayoutText::text)
                .reduce("", String::concat));
        if (!StringUtils.hasText(value)) {
            return emptyField();
        }
        double confidence = candidates.stream()
                .map(LayoutText::confidence)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
        List<String> sourceText = candidates.stream()
                .map(LayoutText::text)
                .filter(StringUtils::hasText)
                .toList();
        return new OcrExtractField(
                value,
                confidence,
                sourceText,
                OcrExtractStatus.EXTRACTED,
                "编号",
                candidates.stream().map(LayoutText::box).toList());
    }

    private boolean isLicenseNumberSource(LayoutText text, String licenseNumber) {
        String normalizedText = normalize(text.text());
        String normalizedLicenseNumber = normalize(licenseNumber);
        return normalizedLicenseNumber.contains(normalizedText)
                || normalizedText.contains("安许")
                || normalizedText.contains("安許")
                || normalizedText.contains("FM")
                || normalizedText.matches(".*\\d{4}.*[A-Za-z]\\d+.*号.*");
    }

    private List<String> labelCandidates(String label) {
        return StringUtils.hasText(label) ? List.of(label) : List.of();
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!isIgnoredSeparator(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private boolean isIgnoredSeparator(char ch) {
        return Character.isWhitespace(ch) || ch == ':' || ch == '：';
    }

    private record LabelHit(LayoutText anchor, String inlineValue, String matchedLabel) {
    }

    private class SameRowValueCollector {

        private final LayoutText anchor;
        private final List<LayoutText> values;
        private final Set<String> stopLabels;
        private final OcrTemplateProperties.Field field;
        private double lastXMax;

        private SameRowValueCollector(
                LayoutText anchor,
                List<LayoutText> values,
                Set<String> stopLabels,
                OcrTemplateProperties.Field field) {
            this.anchor = anchor;
            this.values = values;
            this.stopLabels = stopLabels;
            this.field = field;
            this.lastXMax = values.stream()
                    .filter(value -> isSameRow(value, anchor))
                    .mapToDouble(LayoutText::xMax)
                    .max()
                    .orElse(anchor.xMax());
        }

        private boolean add(LayoutText text) {
            if (values.contains(text)) {
                return false;
            }
            if (text.xMin() - lastXMax > SAME_ROW_MAX_GAP) {
                return true;
            }
            boolean stopped = !addValue(values, text, stopLabels, field);
            lastXMax = Math.max(lastXMax, text.xMax());
            return stopped;
        }
    }

    private record LayoutText(
            String text,
            Double confidence,
            double xMin,
            double yMin,
            double xMax,
            double yMax) {

        private static LayoutText from(OcrLine line, int index) {
            if (line.box() == null || line.box().isEmpty()) {
                double y = index * 30.0;
                return new LayoutText(line.text(), line.confidence(), 0, y, 0, y + 20);
            }
            int xMin = line.box().stream().map(OcrPoint::x).min(Integer::compareTo).orElse(0);
            int yMin = line.box().stream().map(OcrPoint::y).min(Integer::compareTo).orElse(index * 30);
            int xMax = line.box().stream().map(OcrPoint::x).max(Integer::compareTo).orElse(0);
            int yMax = line.box().stream().map(OcrPoint::y).max(Integer::compareTo).orElse(yMin + 20);
            return new LayoutText(line.text(), line.confidence(), xMin, yMin, xMax, yMax);
        }

        private LayoutText withText(String text) {
            return new LayoutText(text, confidence, xMin, yMin, xMax, yMax);
        }

        private double centerY() {
            return (yMin + yMax) / 2;
        }


        private List<OcrPoint> box() {
            return List.of(
                    new OcrPoint((int) Math.round(xMin), (int) Math.round(yMin)),
                    new OcrPoint((int) Math.round(xMax), (int) Math.round(yMin)),
                    new OcrPoint((int) Math.round(xMax), (int) Math.round(yMax)),
                    new OcrPoint((int) Math.round(xMin), (int) Math.round(yMax)));
        }
        private double height() {
            return Math.max(yMax - yMin, 1);
        }
    }
}


