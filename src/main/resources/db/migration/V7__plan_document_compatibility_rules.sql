-- 国家、地方和企业预案中常见的响应章节、字段标记及等级标题写法。
INSERT INTO plan_segment_rule
    (rule_type, rule_code, canonical_name, alias, group_order, alias_order, enabled)
VALUES
    ('RESPONSE', 'level_1', '一级响应', '应急响应（Ⅰ级）', 10, 60, TRUE),
    ('RESPONSE', 'level_1', '一级响应', 'Ⅰ级响应行动', 10, 70, TRUE),
    ('RESPONSE', 'level_1', '一级响应', 'Ⅰ级响应的启动', 10, 80, TRUE),
    ('RESPONSE', 'level_1', '一级响应', '特别重大级应急响应', 10, 90, TRUE),
    ('RESPONSE', 'level_2', '二级响应', '应急响应（Ⅱ级）', 20, 60, TRUE),
    ('RESPONSE', 'level_2', '二级响应', 'Ⅱ级响应行动', 20, 70, TRUE),
    ('RESPONSE', 'level_2', '二级响应', 'Ⅱ级响应的启动', 20, 80, TRUE),
    ('RESPONSE', 'level_2', '二级响应', '重大级应急响应', 20, 90, TRUE),
    ('RESPONSE', 'level_3', '三级响应', '应急响应（Ⅲ级）', 30, 60, TRUE),
    ('RESPONSE', 'level_3', '三级响应', 'Ⅲ级响应行动', 30, 70, TRUE),
    ('RESPONSE', 'level_3', '三级响应', 'Ⅲ级响应的启动', 30, 80, TRUE),
    ('RESPONSE', 'level_3', '三级响应', '较大级应急响应', 30, 90, TRUE),
    ('RESPONSE', 'level_4', '四级响应', '应急响应（Ⅳ级）', 40, 60, TRUE),
    ('RESPONSE', 'level_4', '四级响应', 'Ⅳ级响应行动', 40, 70, TRUE),
    ('RESPONSE', 'level_4', '四级响应', 'Ⅳ级响应的启动', 40, 80, TRUE),
    ('RESPONSE', 'level_4', '四级响应', '一般级应急响应', 40, 90, TRUE),

    ('WARNING', 'warning_level_1', '一级预警', 'Ⅰ级预警响应', 10, 40, TRUE),
    ('WARNING', 'warning_level_2', '二级预警', 'Ⅱ级预警响应', 20, 40, TRUE),
    ('WARNING', 'warning_level_3', '三级预警', 'Ⅲ级预警响应', 30, 40, TRUE),
    ('WARNING', 'warning_level_4', '四级预警', 'Ⅳ级预警响应', 40, 40, TRUE),

    ('SECTION', 'emergency_scope', '应急响应章节', '应急反应', 20, 30, TRUE),
    ('SECTION', 'emergency_scope', '应急响应章节', '响应行动', 20, 40, TRUE),
    ('SECTION', 'emergency_scope', '应急响应章节', '响应程序', 20, 50, TRUE),
    ('SECTION', 'emergency_scope', '应急响应章节', '应急响应分级和启动条件', 20, 60, TRUE),
    ('SECTION', 'action_group_scope', '行动组章节', '应急工作组', 30, 30, TRUE),
    ('SECTION', 'action_group_scope', '行动组章节', '现场工作组', 30, 40, TRUE),

    ('MARKER', 'activation_condition', '启动条件标记', '响应的启动', 10, 40, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '启动标准', 10, 50, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '响应标准', 10, 60, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '事件分级', 10, 70, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '事故分级', 10, 80, TRUE),
    ('MARKER', 'activation_condition', '启动条件标记', '灾害分级', 10, 90, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '响应行动', 20, 40, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '响应程序', 20, 50, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '反应行动', 20, 60, TRUE),
    ('MARKER', 'response_measure', '响应措施标记', '反应措施', 20, 70, TRUE),

    ('TAIL', 'response_tail', '响应结束标题', '响应结束', 10, 70, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '应急响应终止', 10, 80, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '保障措施', 10, 90, TRUE),
    ('TAIL', 'response_tail', '响应结束标题', '恢复重建', 10, 100, TRUE)
ON CONFLICT (rule_type, rule_code, alias) DO UPDATE
SET canonical_name = EXCLUDED.canonical_name,
    group_order = EXCLUDED.group_order,
    alias_order = EXCLUDED.alias_order,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;
