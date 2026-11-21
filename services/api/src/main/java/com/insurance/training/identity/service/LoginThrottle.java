package com.insurance.training.identity.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.service.SystemConfigService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限流。
 *
 * <p>计数键是「用户名摘要 + 来源 IP」的组合，不存明文用户名；
 * 达到阈值后在该时间窗内直接拒绝，避免口令爆破与账号锁定攻击。
 */
@Component
public class LoginThrottle {

    private final StringRedisTemplate redis;
    private final SystemConfigService config;

    public LoginThrottle(StringRedisTemplate redis, SystemConfigService config) {
        this.redis = redis;
        this.config = config;
    }

    public void assertAllowed(String username, String ip) {
        Long count = currentCount(username, ip);
        // 阈值每次判定时读取，配置热改后无需重启；已有计数不清零，只影响后续判定
        if (count != null && count >= config.getInt(SystemConfigKey.LOGIN_MAX_FAILURES)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
        }
    }

    public void onFailure(String username, String ip) {
        String key = key(username, ip);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, config.getDuration(SystemConfigKey.LOGIN_FAILURE_WINDOW));
        }
    }

    public void onSuccess(String username, String ip) {
        redis.delete(key(username, ip));
    }

    private Long currentCount(String username, String ip) {
        String value = redis.opsForValue().get(key(username, ip));
        return value == null ? null : Long.valueOf(value);
    }

    /**
     * 限流计数的 Redis key。
     *
     * <p>用 SHA-256 摘要而不是 {@code String.hashCode()}：后者是 32 位，
     * 不同「账号+IP」组合可能碰撞到同一 key，导致 A 的失败次数把 B 锁死
     * （也可被主动构造碰撞来嫁祸他人）。SHA-256 取前 32 个十六进制字符足够区分，
     * 且仍不落明文用户名。
     */
    private String key(String username, String ip) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((username + "|" + ip).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "auth:fail:" + java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("摘要算法不可用", ex);
        }
    }
}
