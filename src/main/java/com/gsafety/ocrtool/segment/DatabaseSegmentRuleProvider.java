package com.gsafety.ocrtool.segment;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DatabaseSegmentRuleProvider implements SegmentRuleProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSegmentRuleProvider.class);

    private final SegmentRuleRepository repository;
    private final long cacheTtlNanos;
    private volatile CacheEntry cache;

    public DatabaseSegmentRuleProvider(SegmentRuleRepository repository, PlanProperties properties) {
        this.repository = repository;
        Duration configuredTtl = properties.getSegmentRules().getCacheTtl();
        Duration effectiveTtl = configuredTtl == null || configuredTtl.isNegative() || configuredTtl.isZero()
                ? Duration.ofMinutes(1)
                : configuredTtl;
        this.cacheTtlNanos = effectiveTtl.toNanos();
    }

    @Override
    public SegmentRules currentRules() {
        long now = System.nanoTime();
        CacheEntry current = cache;
        if (current != null && now < current.refreshAfterNanos()) {
            return current.rules();
        }
        synchronized (this) {
            current = cache;
            now = System.nanoTime();
            if (current != null && now < current.refreshAfterNanos()) {
                return current.rules();
            }
            try {
                SegmentRules loaded = loadRules(repository.findEnabledRules());
                cache = new CacheEntry(loaded, now + cacheTtlNanos);
                return loaded;
            } catch (DataAccessException | IllegalStateException ex) {
                if (current != null) {
                    log.warn("预案分段规则刷新失败，继续使用上一版缓存。", ex);
                    cache = new CacheEntry(current.rules(), now + cacheTtlNanos);
                    return current.rules();
                }
                throw new OcrException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "SEGMENT_RULES_UNAVAILABLE",
                        "预案分段规则加载失败。",
                        ex);
            }
        }
    }

    private SegmentRules loadRules(List<SegmentRuleRow> rows) {
        String commandKey = null;
        List<String> commandAliases = new ArrayList<>();
        List<String> responseTailHeadings = new ArrayList<>();
        Map<String, RuleGroup> responseGroups = new LinkedHashMap<>();
        Map<String, RuleGroup> warningGroups = new LinkedHashMap<>();
        Map<String, RuleGroup> sectionGroups = new LinkedHashMap<>();
        Map<String, RuleGroup> markerGroups = new LinkedHashMap<>();

        for (SegmentRuleRow row : rows) {
            validateRow(row);
            switch (row.ruleType()) {
                case "COMMAND" -> {
                    if (commandKey == null) {
                        commandKey = row.ruleCode();
                    } else if (!commandKey.equals(row.ruleCode())) {
                        throw new IllegalStateException("当前只支持一个指挥体系规则分组。");
                    }
                    commandAliases.add(row.alias());
                }
                case "TAIL" -> responseTailHeadings.add(row.alias());
                case "RESPONSE" -> addAlias(responseGroups, row);
                case "WARNING" -> addAlias(warningGroups, row);
                case "SECTION" -> addAlias(sectionGroups, row);
                case "MARKER" -> addAlias(markerGroups, row);
                default -> throw new IllegalStateException("未知的预案分段规则类型：" + row.ruleType());
            }
        }

        if (commandKey == null || commandAliases.isEmpty() || responseGroups.isEmpty() || responseTailHeadings.isEmpty()) {
            throw new IllegalStateException("预案分段规则不完整，COMMAND、RESPONSE、TAIL 均不能为空。");
        }

        Map<String, List<String>> responseAliases = new LinkedHashMap<>();
        Map<String, String> responseKeys = new LinkedHashMap<>();
        publishLevelGroups(responseGroups, responseAliases, responseKeys, "响应");
        Map<String, List<String>> warningAliases = new LinkedHashMap<>();
        Map<String, String> warningKeys = new LinkedHashMap<>();
        publishLevelGroups(warningGroups, warningAliases, warningKeys, "预警");
        return new SegmentRules(
                commandKey,
                commandAliases,
                responseAliases,
                responseKeys,
                warningAliases,
                warningKeys,
                aliasesByCode(sectionGroups),
                aliasesByCode(markerGroups),
                responseTailHeadings);
    }

    private void validateRow(SegmentRuleRow row) {
        if (!StringUtils.hasText(row.ruleType())
                || !StringUtils.hasText(row.ruleCode())
                || !StringUtils.hasText(row.canonicalName())
                || !StringUtils.hasText(row.alias())) {
            throw new IllegalStateException("预案分段规则存在空字段。");
        }
    }

    private void addAlias(Map<String, RuleGroup> groups, SegmentRuleRow row) {
        RuleGroup group = groups.computeIfAbsent(
                row.ruleCode(),
                ignored -> new RuleGroup(row.canonicalName(), new ArrayList<>()));
        if (!group.canonicalName().equals(row.canonicalName())) {
            throw new IllegalStateException("同一规则编码配置了不同标准名称：" + row.ruleCode());
        }
        group.aliases().add(row.alias());
    }

    private void publishLevelGroups(
            Map<String, RuleGroup> groups,
            Map<String, List<String>> aliases,
            Map<String, String> keys,
            String label) {
        groups.forEach((ruleCode, group) -> {
            if (aliases.put(group.canonicalName(), List.copyOf(group.aliases())) != null) {
                throw new IllegalStateException(label + "规则标准名称重复：" + group.canonicalName());
            }
            keys.put(group.canonicalName(), ruleCode);
        });
    }

    private Map<String, List<String>> aliasesByCode(Map<String, RuleGroup> groups) {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        groups.forEach((code, group) -> aliases.put(code, List.copyOf(group.aliases())));
        return aliases;
    }

    private record CacheEntry(SegmentRules rules, long refreshAfterNanos) {
    }

    private record RuleGroup(String canonicalName, List<String> aliases) {
    }
}
