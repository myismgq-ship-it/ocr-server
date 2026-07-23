package com.gsafety.ocrtool.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
/**
 * 安全生产许可证模板的专用后处理器。
 *
 * <p>企业名称、许可证号和日期的 OCR 容错规则集中在这里，
 * 避免“第2→02”等领域修正误用于其他证照模板。</p>
 */

@Component
public class SafetyLicenseTemplateProcessor {
    /** 标准中文日期格式。 */

    /** 兼容常见 OCR 错字和括号形态的许可证号模式。 */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}年\\d{1,2}月\\d{1,2}日");
    private static final Pattern LICENSE_NUMBER_PATTERN = Pattern.compile(
            "[（(〔\\[]?[\\u4e00-\\u9fa5A-Za-z]{1,4}[）)〕\\]]?[A-Z]{0,4}安[许許午][证証][字学]?"
                    + "[（(〔\\[]?\\d{4}[）)〕\\]]?[A-Za-z0-9-]{1,12}[号號]");
    /**
     * 按字段名应用许可证专用修正，同时保留原置信度和来源证据。
     *
     * @return 修正后的不可变字段结果
     */

    public OcrExtractField process(String fieldName, OcrExtractField field) {
        if (!StringUtils.hasText(field.value())) {
            return field;
        }
        // 只有明确的许可证字段名进入专用规则，未知字段原样返回。
        String value = field.value();
        if ("enterpriseName".equals(fieldName)) {
            value = beforeAny(value, List.of("许可范围", "可范围", "经营范围"));
            value = value.replace("有限公", "有限公司");
        } else if ("validPeriod".equals(fieldName)) {
            value = dateRange(repairDateText(value));
        } else if ("issueDate".equals(fieldName)) {
            value = singleDate(repairDateText(value));
        } else if ("licenseNumber".equals(fieldName)) {
            String extracted = extractLicenseNumber(value);
            value = StringUtils.hasText(extracted) ? extracted : value;
        }
        return new OcrExtractField(
                StringUtils.hasText(value) ? value : field.value(),
                field.confidence(),
                field.sourceText(),
                field.status(),
                field.matchedLabel(),
                field.sourceBoxes());
    }

    public String extractLicenseNumber(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
    /**
     * 从任意 OCR 文本中提取第一个符合格式的许可证编号。
     */
        }
        Matcher matcher = LICENSE_NUMBER_PATTERN.matcher(value.replaceAll("\\s+", ""));
        return matcher.find() ? matcher.group() : null;
    }

    private String repairDateText(String value) {
        if (!StringUtils.hasText(value) || !value.contains("月") || !value.contains("日")) {
            return value;
        }
        return value
                .replace("第2", "02")
                .replaceAll("(\\d{4})(\\d{1,2}月)", "$1年$2")
                .replaceAll("年(\\d月)", "年0$1")
                .replaceAll("月(\\d日)", "月0$1");
    }

    private String beforeAny(String value, List<String> markers) {
        int end = -1;
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0 && (end < 0 || index < end)) {
                end = index;
            }
        }
        return end < 0 ? value : value.substring(0, end);
    }

    private String dateRange(String value) {
        List<String> dates = dates(value);
        return dates.size() >= 2 ? dates.get(0) + "至" + dates.get(1) : value;
    }

    private String singleDate(String value) {
        value = value.replace("每月", "02月")
                .replace("第2月", "02月")
                .replace("第2", "02");
        List<String> dates = dates(value);
        if (!dates.isEmpty()) {
            return dates.get(0);
        }
        Matcher partial = Pattern.compile("(\\d{4}).*?(\\d{1,2})?月(\\d{1,2})日").matcher(value);
        if (partial.find()) {
            String month = StringUtils.hasText(partial.group(2)) ? partial.group(2) : "02";
            return partial.group(1) + "年" + pad2(month) + "月" + pad2(partial.group(3)) + "日";
        }
        return value;
    }

    private List<String> dates(String value) {
        Matcher matcher = DATE_PATTERN.matcher(value);
        List<String> dates = new ArrayList<>();
        while (matcher.find()) {
            dates.add(matcher.group()
                    .replaceAll("年(\\d月)", "年0$1")
                    .replaceAll("月(\\d日)", "月0$1"));
        }
        return dates;
    }

    private String pad2(String value) {
        return value.length() == 1 ? "0" + value : value;
    }
}
