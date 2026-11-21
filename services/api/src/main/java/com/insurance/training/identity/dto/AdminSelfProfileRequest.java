package com.insurance.training.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员自助修改显示名。 */
public record AdminSelfProfileRequest(
        @NotBlank(message = "显示名不能为空")
        @Size(max = 64, message = "显示名不能超过 64 个字符")
        String displayName) {
}
