package com.gsafety.ocrtool.management;

/**
 * 一条规则在文档块中的候选命中。
 *
 * @param blockIndex 文档有序块下标，便于与其他候选比对
 * @param page 来源页码
 * @param alias 触发候选的规则别名
 * @param text 经过长度限制的原文片段
 * @param disposition 中文候选判定，例如“正文标题候选”或“目录项，已排除”
 */
public record RuleDebugCandidateResponse(
        int blockIndex,
        int page,
        String alias,
        String text,
        String disposition) {
}
