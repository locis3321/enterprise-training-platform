package com.insurance.training.system.vo;

import java.time.LocalDateTime;

/**
 * 系统设置项视图。
 *
 * <p>{@code source} 明确标注生效值来自哪里：{@code DATABASE}（已被覆盖）或 {@code DEFAULT}（出厂默认）。
 * 不返回任何密钥类值——注册表本身就不含密钥。
 */
public record SystemSettingVo(
        String key,
        String group,
        String label,
        String description,
        String valueType,
        String effectiveValue,
        String source,
        String defaultValue,
        LocalDateTime updatedAt,
        Long updatedByAdminId) {
}
