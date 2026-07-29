-- 自然灾害救助预案常在具体等级下以“灾害损失情况”列出启动阈值。
INSERT INTO plan_segment_rule
    (rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled)
VALUES
    ('MARKER', 'activation_condition', '启动条件标记', '灾害损失情况', 10, 120, TRUE)
ON CONFLICT (rule_type, rule_code, alias) DO UPDATE
SET canonical_name = EXCLUDED.canonical_name,
    group_order = EXCLUDED.group_order,
    alias_order = EXCLUDED.alias_order,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
