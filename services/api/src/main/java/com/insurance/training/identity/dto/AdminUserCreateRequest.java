package com.insurance.training.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建管理端账号。归属由角色决定，不接受请求体自证范围。 */
public record AdminUserCreateRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在 3-32 位之间")
        String username,
        @NotBlank(message = "初始密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
        String password,
        @NotBlank(message = "显示名不能为空")
        @Size(max = 64, message = "显示名不能超过 64 个字符")
        String displayName,
        @NotBlank(message = "角色不能为空")
        String roleCode,
        Long insuranceCompanyId,
        Long enterpriseId) {
}
