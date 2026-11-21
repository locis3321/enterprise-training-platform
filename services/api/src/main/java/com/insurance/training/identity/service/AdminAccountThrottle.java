package com.insurance.training.identity.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 账号管理操作限流。
 *
 * <p>与 {@link LoginThrottle} 使用**不同 key 空间**，无法互相影响：
 * 管理员改密失败不会锁死目标账号的登录，登录爆破也不会耗尽改密额度。
 *
 * <p>两者都按操作者单维度计数（不含 IP）：token 已确定身份，掺入 IP 会给出
 * 「轮换 IP 绕过」的口子。登录场景掺 IP 是因为当时身份尚未确定，不可照搬。
 */
@Component
public class AdminAccountThrottle {

    /** 重置密码：计所有操作（重置不校验旧密码，无「失败」可计）。 */
    private static final int RESET_LIMIT = 10;
    private static final Duration RESET_WINDOW = Duration.ofMinutes(5);

    /** 自助改密：只计旧密码校验失败。 */
    private static final int SELF_PWD_LIMIT = 5;
    private static final Duration SELF_PWD_WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public AdminAccountThrottle(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void assertResetAllowed(long operatorAdminId) {
        Long count = read("admin:pwdreset:" + digest(operatorAdminId));
        if (count != null && count >= RESET_LIMIT) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "重置密码操作过于频繁，请稍后再试");
        }
    }

    public void onReset(long operatorAdminId) {
        increment("admin:pwdreset:" + digest(operatorAdminId), RESET_WINDOW);
    }

    public void assertSelfPasswordAllowed(long adminId) {
        Long count = read("admin:selfpwd:" + digest(adminId));
        if (count != null && count >= SELF_PWD_LIMIT) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "改密失败次数过多，请稍后再试");
        }
    }

    public void onSelfPasswordFailure(long adminId) {
        increment("admin:selfpwd:" + digest(adminId), SELF_PWD_WINDOW);
    }

    public void onSelfPasswordSuccess(long adminId) {
        redis.delete("admin:selfpwd:" + digest(adminId));
    }

    private void increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
    }

    private Long read(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? null : Long.valueOf(value);
    }

    /** 与 LoginThrottle 一致：SHA-256 取前 32 位十六进制，避免哈希碰撞嫁祸。 */
    private String digest(long adminId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(adminId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("摘要算法不可用", ex);
        }
    }
}
