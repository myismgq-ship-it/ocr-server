package com.gsafety.ocrtool.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Shared text cleanup and heading inference for Word-compatible document carriers. */
final class DocumentTextNormalizer {

    private static final Pattern ARABIC_NUMBERED_HEADING = Pattern.compile(
            "^(\\d+(?:\\.\\d+){0,5})(?:[、.\\s　]+|(?=[\\p{IsHan}])).{0,80}");
    private static final Pattern LEGAL_HEADING = Pattern.compile(
            "^第[〇零一二三四五六七八九十百千万0-9]+[章节篇条].{0,80}");
    private static final Pattern CHINESE_NUMBERED_HEADING = Pattern.compile(
            "^[〇零一二三四五六七八九十百千万]+[、.].{0,80}");
    private static final Pattern NUMBERING_PREFIX = Pattern.compile("^[0-9０-９lLI]+(?:[.．][0-9０-９lLI]+)+");

    private DocumentTextNormalizer() {
    }

    static List<String> split(String text) {
        if (text == null) {
            return List.of();
        }
        String value = text.replace('\u000b', '\n').replace('\f', '\n');
        List<String> parts = new ArrayList<>();
        for (String line : value.split("[\\r\\n]+")) {
            String cleaned = clean(line);
            if (StringUtils.hasText(cleaned)) {
                parts.add(cleaned);
            }
        }
        return List.copyOf(parts);
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String value = normalizeFullWidthDigits(text)
                .replace('\u00a0', ' ')
                .replace("\uFEFF", "")
                .replace('．', '.')
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return repairNumberingPrefix(value);
    }

    static int inferHeadingLevel(String text) {
        String value = clean(text);
        if (!StringUtils.hasText(value) || value.length() > 80) {
            return 0;
        }
        if (LEGAL_HEADING.matcher(value).matches()) {
            return 1;
        }
        Matcher arabic = ARABIC_NUMBERED_HEADING.matcher(value);
        if (arabic.matches()) {
            int level = 1 + (int) arabic.group(1).chars().filter(character -> character == '.').count();
            return clampHeadingLevel(level);
        }
        return CHINESE_NUMBERED_HEADING.matcher(value).matches() ? 2 : 0;
    }

    static int clampHeadingLevel(int level) {
        return Math.max(1, Math.min(level, 6));
    }

    private static String normalizeFullWidthDigits(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            normalized.append(value >= '０' && value <= '９' ? (char) ('0' + value - '０') : value);
        }
        return normalized.toString();
    }

    private static String repairNumberingPrefix(String text) {
        Matcher matcher = NUMBERING_PREFIX.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        String prefix = matcher.group().replace('l', '1').replace('L', '1').replace('I', '1');
        return prefix + text.substring(matcher.end());
    }
}
