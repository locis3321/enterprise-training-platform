package com.insurance.training.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员自助改密，必须校验旧密码。 */
public record AdminSelfPasswordRequest(
        @NotBlank(message = "旧密码不能为空")
        String oldPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
        String newPassword) {
}
