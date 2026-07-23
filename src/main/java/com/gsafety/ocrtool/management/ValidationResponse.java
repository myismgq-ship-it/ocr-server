package com.gsafety.ocrtool.management;

import java.util.List;

/**
 * 模板或规则草稿的校验结果。
 *
 * @param valid 是否可以发布/测试
 * @param errors 全部校验错误；成功时为空列表
 */

public record ValidationResponse(
        boolean valid,
        List<String> errors) {
}
