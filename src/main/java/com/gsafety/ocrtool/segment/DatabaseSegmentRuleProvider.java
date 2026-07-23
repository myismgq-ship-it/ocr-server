package com.gsafety.ocrtool.segment;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.config.PlanProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
/**
 * 从数据库加载并缓存预案分段规则的提供器。
 *
 * <p>每次返回不可变快照；数据库短暂异常时可继续使用上一版缓存，
 * 但应用首次加载且没有可用快照时会明确返回服务不可用。</p>
 */

@Component
public class DatabaseSegmentRuleProvider implements SegmentRuleProvider {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSegmentRuleProvider.class);
    /** 已启用规则的只读查询入口。 */

    /** 规则缓存有效期，使用单调时钟避免系统时间回拨影响。 */
    private final SegmentRuleRepository repository;
    /** volatile 保证刷新后的不可变快照对所有解析线程可见。 */
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
    /**
     * 获取当前规则快照；缓存过期时仅允许一个线程刷新。
     */
    public SegmentRules currentRules() {
        long now = System.nanoTime();
        CacheEntry current = cache;
        if (current != null && now < current.refreshAfterNanos()) {
        // 未过期时走无锁快速路径，整份文档只持有返回的同一个快照。
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
                    // 已有快照优先保证可用性，刷新异常不会中断正在处理的文档。
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

    /**
     * 发布新规则事务提交后主动使缓存失效。
     */
    public void invalidate() {
        cache = null;
    }
    /**
     * 从未发布的规则行创建隔离快照，供样本文档测试使用，不写入缓存。
     *
     * @param rows 已按组和别名顺序排列的规则行
     */
    public SegmentRules createSnapshot(List<SegmentRuleRow> rows) {
        return loadRules(List.copyOf(rows));
    }

    /**
     * 校验并组装 COMMAND、RESPONSE、WARNING、SECTION、MARKER 和 TAIL 规则。
     */

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
                responseTailHeadings,
    /**
     * 对有序规则内容计算稳定 SHA-256 摘要，作为结果可复现的规则版本。
     */
                version(rows));
    }

    private String version(List<SegmentRuleRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (SegmentRuleRow row : rows) {
                String value = String.join(
                        "\u001f", row.ruleType(), row.ruleCode(), row.canonicalName(), row.alias());
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] bytes = digest.digest();
            StringBuilder version = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                version.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i]));
            }
            return version.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 不支持 SHA-256。", ex);
        }
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
