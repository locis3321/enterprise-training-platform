package com.insurance.training.employee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 员工新增/修改。手机号为企业内唯一。 */
public record EmployeeRequest(
        @NotNull(message = "所属企业不能为空")
        Long enterpriseId,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名长度不能超过 64")
        String name,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Pattern(regexp = "^$|^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
        String idCardNo,

        Integer status
) {
}
