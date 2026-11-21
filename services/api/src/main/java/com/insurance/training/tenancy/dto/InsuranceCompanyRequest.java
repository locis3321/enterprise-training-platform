package com.insurance.training.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 集团新增/修改。 */
public record InsuranceCompanyRequest(
        @NotBlank(message = "集团名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @NotBlank(message = "编码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_-]{2,64}$", message = "编码只能包含字母、数字、下划线与短横线，长度 2-64")
        String code,

        @Size(max = 64, message = "联系人长度不能超过 64")
        String contactName,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "联系电话格式不正确")
        String contactPhone,

        Integer status
) {
}
