package com.insurance.training.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务。
 *
 * <p>目前唯一的定时任务是系统配置的 30 秒全量刷新（{@code SystemConfigService.scheduledRefresh}）。
 * 引入前已确认全仓没有其他 {@code @Scheduled}，不会意外激活未知任务。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
