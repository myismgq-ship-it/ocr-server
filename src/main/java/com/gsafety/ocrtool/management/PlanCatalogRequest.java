package com.gsafety.ocrtool.management;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增或修改预案目录的请求。
 *
 * @param planId 预案稳定业务 ID；修改接口使用路径中的 ID，可不传
 * @param code 预案业务编码
 * @param name 预案名称
 * @param category 事件分类
 * @param department 编制或主管部门
 * @param version 版本标识
 */
public record PlanCatalogRequest(
        @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$", message = "预案 ID 只能包含字母、数字、点、下划线和短横线。")
        String planId,
        @Size(max = 64, message = "预案编码不能超过 64 个字符。")
        String code,
        @NotBlank(message = "预案名称不能为空。")
        @Size(max = 256, message = "预案名称不能超过 256 个字符。")
        String name,
        @Size(max = 128, message = "事件分类不能超过 128 个字符。")
        String category,
        @Size(max = 256, message = "主管部门不能超过 256 个字符。")
        String department,
        @Size(max = 64, message = "版本标识不能超过 64 个字符。")
        String version) {
}
