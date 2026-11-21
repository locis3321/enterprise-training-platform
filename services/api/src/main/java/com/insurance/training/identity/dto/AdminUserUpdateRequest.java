package com.insurance.training.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 编辑管理端账号。用户名与密码不在此接口修改。 */
public record AdminUserUpdateRequest(
        @NotBlank(message = "显示名不能为空")
        @Size(max = 64, message = "显示名不能超过 64 个字符")
        String displayName,
        @NotBlank(message = "角色不能为空")
        String roleCode,
        Long insuranceCompanyId,
        Long enterpriseId,
        Integer status) {
}
