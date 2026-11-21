package com.insurance.training.system.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 系统设置整体视图：设置项 + 刷新健康状态。 */
public record SystemSettingsViewVo(
        List<SystemSettingVo> items,
        LocalDateTime lastRefreshAt,
        String lastRefreshError,
        String effectiveNotice) {
}
