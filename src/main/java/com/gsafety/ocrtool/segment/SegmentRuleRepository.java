package com.gsafety.ocrtool.segment;
import com.gsafety.ocrtool.management.PlanRuleDefinition;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SegmentRuleRepository {

    private static final String SELECT_ENABLED_RULES = """
            SELECT rule_type, rule_code, canonical_name, alias
            FROM plan_segment_rule
            WHERE enabled = TRUE
            ORDER BY rule_type, group_order, rule_code, alias_order, id
            """;

    private final JdbcTemplate jdbcTemplate;

    public SegmentRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<SegmentRuleRow> findEnabledRules() {
        return jdbcTemplate.query(
                SELECT_ENABLED_RULES,
                (resultSet, rowNum) -> new SegmentRuleRow(
                        resultSet.getString("rule_type"),
                        resultSet.getString("rule_code"),
                        resultSet.getString("canonical_name"),
                        resultSet.getString("alias")));
    }
    /**
     * Reads enabled database rows with their display order for administration and debugging.
     * Runtime parsing still uses {@link #findEnabledRules()} to build its immutable snapshot.
     */
    public List<PlanRuleDefinition> findEnabledRuleDefinitions() {
        return jdbcTemplate.query(
                "SELECT rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled "
                        + "FROM plan_segment_rule WHERE enabled = TRUE "
                        + "ORDER BY rule_type, group_order, rule_code, alias_order, id",
                (resultSet, rowNum) -> new PlanRuleDefinition(
                        resultSet.getString("rule_type"),
                        resultSet.getString("rule_code"),
                        resultSet.getString("canonical_name"),
                        resultSet.getString("alias"),
                        resultSet.getInt("group_order"),
                        resultSet.getInt("alias_order"),
                        resultSet.getBoolean("enabled")));
    }
}
