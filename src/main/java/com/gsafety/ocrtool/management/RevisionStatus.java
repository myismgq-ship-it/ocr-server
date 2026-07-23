package com.gsafety.ocrtool.management;

/** 模板和规则修订的生命周期状态。 */
public enum RevisionStatus {
    /** 可编辑、可校验、尚未生效。 */
    DRAFT,
    /** 当前线上唯一生效版本。 */
    PUBLISHED,
    /** 历史版本，仅用于查询、测试或回滚来源。 */
    ARCHIVED
}
