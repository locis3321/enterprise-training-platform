package com.insurance.training.learning.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.service.SystemConfigService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 学员学习接口限流。
 *
 * <p>为什么需要：心跳会触发进度全量重算（章节/课程/任务三级）。若不限流，
 * 脚本高频调用可把数据库打满；同时正常客户端是 30 秒一次，5 秒内重复毫无意义。
 *
 * <p>实现用 Redis {@code SET NX EX} 的「最小间隔」语义（不是滑动窗口计数）：
 * 只关心「距上次成功放行是否够久」，一条 key 即可，无桶大小参数需要调。
 * 超出间隔直接 429，让客户端按自己的节奏重试——正常客户端永远不会触发。
 */
@Component
public class LearningRateLimiter {

    private final StringRedisTemplate redis;
    private final SystemConfigService config;

    public LearningRateLimiter(StringRedisTemplate redis, SystemConfigService config) {
        this.redis = redis;
        this.config = config;
    }

    /** 心跳限流：同一 assignment 在最小间隔内重复请求 → 429。间隔每次读取，配置热改即生效。 */
    public void assertHeartbeatAllowed(long assignmentId) {
        assertAllowed("learn:hb:" + assignmentId,
                config.getDuration(SystemConfigKey.LEARNING_HEARTBEAT_MIN_INTERVAL),
                "心跳过于频繁，请稍后再试");
    }

    private void assertAllowed(String key, Duration interval, String message) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", interval);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, message);
        }
    }
}
