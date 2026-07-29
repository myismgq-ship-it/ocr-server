-- 跨章节启动条件常使用“险情/灾情分级”定义等级阈值，再在后文描述响应措施。
INSERT INTO plan_segment_rule
    (rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled)
VALUES
    ('MARKER', 'activation_condition', '启动条件标记', '险情与灾情分级', 10, 100, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '灾情分级', 10, 110, TRUE)
ON CONFLICT (rule_type, rule_code, alias) DO UPDATE
SET canonical_name = EXCLUDED.canonical_name,
    group_order = EXCLUDED.group_order,
    alias_order = EXCLUDED.alias_order,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
