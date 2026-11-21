package com.insurance.training.learning.service;

import com.insurance.training.common.config.JacksonConfig;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 业务时钟。
 *
 * <p>学习时间窗与心跳计时只信服务端时间；单独成组件以便测试用固定时刻驱动，
 * 避免测试依赖真实 wall clock 造成偶发失败。
 */
@Component
public class BusinessClock {

    private static final ZoneId ZONE = ZoneId.of(JacksonConfig.BUSINESS_TIME_ZONE);

    public LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}