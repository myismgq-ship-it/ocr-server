package com.gsafety.ocrtool.management;

import com.gsafety.ocrtool.common.OcrException;
import com.gsafety.ocrtool.segment.DatabaseSegmentRuleProvider;
import com.gsafety.ocrtool.segment.SegmentRuleRow;
import com.gsafety.ocrtool.segment.SegmentRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 预案分段规则的版本管理和发布服务。
 *
 * <p>规则发布与活动规则表替换处于同一事务，缓存只在事务成功提交后失效。</p>
 */
@Service
public class PlanRuleRevisionService {

    /** 管理端允许提交的稳定规则类型。 */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("COMMAND", "RESPONSE", "WARNING", "SECTION", "MARKER", "TAIL");

    /** 规则修订表和活动规则表读写入口。 */
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    /** 将有序规则行编译为不可变运行时快照。 */
    private final DatabaseSegmentRuleProvider ruleProvider;

    public PlanRuleRevisionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DatabaseSegmentRuleProvider ruleProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.ruleProvider = ruleProvider;
    }

    /**
     * 创建下一规则版本草稿，不修改当前线上活动规则。
     */
    @Transactional
    public PlanRuleRevisionResponse createDraft(
            List<PlanRuleDefinition> rules,
            String createdBy) {
        lock();
        // 全局事务锁保护 MAX + 1 版本号分配，避免并发草稿冲突。
        List<PlanRuleDefinition> safeRules = rules == null ? List.of() : List.copyOf(rules);
        int next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_number), 0) + 1 FROM plan_rule_revision",
                Integer.class);
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO plan_rule_revision "
                        + "(revision_id, revision_number, status, definition, created_by, created_at) "
                        + "VALUES (?, ?, 'DRAFT', CAST(? AS jsonb), ?, ?)",
                revisionId,
                next,
                write(safeRules),
                caller(createdBy),
                now);
        return get(revisionId);
    }

    /** 查询全部规则修订历史。 */
    public List<PlanRuleRevisionResponse> history() {
        return jdbc.query(
                "SELECT revision_id, revision_number, status, definition::text, "
                        + "created_by, created_at, published_at "
                        + "FROM plan_rule_revision ORDER BY revision_number DESC",
                this::mapRow);
    }

    /** 查询指定规则修订。 */
    public PlanRuleRevisionResponse get(UUID revisionId) {
        return jdbc.query(
                        "SELECT revision_id, revision_number, status, definition::text, "
                                + "created_by, created_at, published_at "
                                + "FROM plan_rule_revision WHERE revision_id = ?",
                        this::mapRow,
                        revisionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new OcrException(
                        HttpStatus.NOT_FOUND,
                        "RULE_REVISION_NOT_FOUND",
                        "预案规则修订不存在。"));
    }

    /** 校验规则类型、必填字段、重复别名和必要分组。 */
    public ValidationResponse validate(UUID revisionId) {
        return validateRules(get(revisionId).rules());
    }

    /**
     * 编译指定修订为隔离规则快照，供上传文档测试。
     *
     * <p>该方法不写活动规则表，也不修改当前缓存。</p>
     */
    public SegmentRules snapshot(UUID revisionId) {
        PlanRuleRevisionResponse revision = get(revisionId);
        ValidationResponse validation = validateRules(revision.rules());
        if (!validation.valid()) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "RULE_REVISION_INVALID",
                    "预案规则校验失败：" + String.join("；", validation.errors()));
        }
        List<SegmentRuleRow> rows = revision.rules().stream()
                .filter(PlanRuleDefinition::enabled)
                .sorted(Comparator.comparingInt(PlanRuleDefinition::groupOrder)
                        .thenComparingInt(PlanRuleDefinition::aliasOrder))
                .map(rule -> new SegmentRuleRow(
                        rule.ruleType(),
                        rule.ruleCode(),
                        rule.canonicalName(),
                        rule.alias()))
                .toList();
        return ruleProvider.createSnapshot(rows);
    }

    /**
     * 原子发布规则草稿：替换活动规则、归档旧版本并发布新版本。
     *
     * <p>任一 SQL 失败都会回滚，解析线程不会看到部分更新。</p>
     */
    @Transactional
    public PlanRuleRevisionResponse publish(UUID revisionId) {
        lock();
        PlanRuleRevisionResponse revision = get(revisionId);
        ValidationResponse validation = validateRules(revision.rules());
        if (!validation.valid()) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "RULE_REVISION_INVALID",
                    "预案规则校验失败：" + String.join("；", validation.errors()));
        }
        if (!RevisionStatus.DRAFT.name().equals(revision.status())) {
            throw new OcrException(
                    HttpStatus.CONFLICT,
                    "RULE_REVISION_NOT_DRAFT",
                    "只有草稿状态的规则可以发布。");
        }

        // 仅写入启用规则，活动表始终是一个可直接加载的完整快照。
        jdbc.update("DELETE FROM plan_segment_rule");
        List<PlanRuleDefinition> enabled = revision.rules().stream()
                .filter(PlanRuleDefinition::enabled)
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO plan_segment_rule "
                        + "(rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE)",
                enabled,
                enabled.size(),
                (statement, rule) -> {
                    statement.setString(1, rule.ruleType());
                    statement.setString(2, rule.ruleCode());
                    statement.setString(3, rule.canonicalName());
                    statement.setString(4, rule.alias());
                    statement.setInt(5, rule.groupOrder());
                    statement.setInt(6, rule.aliasOrder());
                });
        jdbc.update("UPDATE plan_rule_revision SET status = 'ARCHIVED' WHERE status = 'PUBLISHED'");
        int updated = jdbc.update(
                "UPDATE plan_rule_revision SET status = 'PUBLISHED', published_at = ? "
                        + "WHERE revision_id = ? AND status = 'DRAFT'",
                OffsetDateTime.now(),
                revisionId);
        if (updated == 0) {
            throw new OcrException(
                    HttpStatus.CONFLICT,
                    "RULE_PUBLISH_CONFLICT",
                    "预案规则发布状态已变化。");
        }
        invalidateAfterCommit();
        return get(revisionId);
    }

    /**
     * 复制历史修订为新草稿并发布，不修改历史记录。
     */
    @Transactional
    public PlanRuleRevisionResponse rollback(UUID sourceRevisionId, String createdBy) {
        PlanRuleRevisionResponse source = get(sourceRevisionId);
        PlanRuleRevisionResponse draft = createDraft(source.rules(), createdBy);
        return publish(draft.revisionId());
    }

    /**
     * 执行不产生数据库副作用的规则结构校验。
     */
    ValidationResponse validateRules(List<PlanRuleDefinition> rules) {
        List<String> errors = new ArrayList<>();
        if (rules == null || rules.isEmpty()) {
            return new ValidationResponse(false, List.of("规则列表不能为空"));
        }
        Set<String> uniqueAliases = new HashSet<>();
        boolean command = false;
        boolean response = false;
        boolean tail = false;
        for (int i = 0; i < rules.size(); i++) {
            PlanRuleDefinition rule = rules.get(i);
            String prefix = "rules[" + i + "]";
            if (rule == null
                    || !StringUtils.hasText(rule.ruleType())
                    || !StringUtils.hasText(rule.ruleCode())
                    || !StringUtils.hasText(rule.canonicalName())
                    || !StringUtils.hasText(rule.alias())) {
                errors.add(prefix + " 必填字段不完整");
                continue;
            }
            if (!ALLOWED_TYPES.contains(rule.ruleType())) {
                errors.add(prefix + ".ruleType 不受支持");
            }
            if (!uniqueAliases.add(rule.ruleType() + "\u001f" + rule.ruleCode() + "\u001f" + rule.alias())) {
                errors.add(prefix + " 存在重复别名");
            }
            if (rule.enabled()) {
                command |= "COMMAND".equals(rule.ruleType());
                response |= "RESPONSE".equals(rule.ruleType());
                tail |= "TAIL".equals(rule.ruleType());
            }
        }
        if (!command || !response || !tail) {
            errors.add("启用规则必须同时包含 COMMAND、RESPONSE 和 TAIL");
        }
        return new ValidationResponse(errors.isEmpty(), List.copyOf(errors));
    }

    private PlanRuleRevisionResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            PlanRuleDefinition[] rules =
                    objectMapper.readValue(rs.getString(4), PlanRuleDefinition[].class);
            return new PlanRuleRevisionResponse(
                    rs.getObject(1, UUID.class),
                    rs.getInt(2),
                    rs.getString(3),
                    List.of(rules),
                    rs.getString(5),
                    rs.getObject(6, OffsetDateTime.class),
                    rs.getObject(7, OffsetDateTime.class));
        } catch (Exception ex) {
            throw new SQLException("预案规则修订读取失败。", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new OcrException(
                    HttpStatus.BAD_REQUEST,
                    "RULE_REVISION_INVALID",
                    "预案规则定义无法序列化。",
                    ex);
        }
    }

    /**
     * 在事务提交成功后清除规则缓存；回滚时继续保留原快照。
     *
     * <p>避免活动规则 SQL 尚未提交，其他线程就提前刷新到不一致数据。</p>
     */
    private void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ruleProvider.invalidate();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ruleProvider.invalidate();
            }
        });
    }
    /**
     * 获取规则版本管理的全局事务级 advisory lock。
     */
    private void lock() {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('plan-rule-revision', 0))",
                (ResultSetExtractor<Void>) resultSet -> null);
    }


    private String caller(String value) {
        return StringUtils.hasText(value) ? value.trim() : "gateway-unknown";
    }
}
