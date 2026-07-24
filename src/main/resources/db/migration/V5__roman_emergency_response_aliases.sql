-- 扩展罗马数字等级标题，兼容“Ⅰ级应急响应”等常见预案写法。
INSERT INTO plan_segment_rule
    (rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled)
VALUES
    ('RESPONSE', 'level_1', '一级响应', 'Ⅰ级应急响应', 10, 35, TRUE),
    ('RESPONSE', 'level_2', '二级响应', 'Ⅱ级应急响应', 20, 35, TRUE),
    ('RESPONSE', 'level_3', '三级响应', 'Ⅲ级应急响应', 30, 35, TRUE),
    ('RESPONSE', 'level_4', '四级响应', 'Ⅳ级应急响应', 40, 35, TRUE)
ON CONFLICT (rule_type, rule_code, alias) DO UPDATE
SET canonical_name = EXCLUDED.canonical_name,
    group_order = EXCLUDED.group_order,
    alias_order = EXCLUDED.alias_order,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
