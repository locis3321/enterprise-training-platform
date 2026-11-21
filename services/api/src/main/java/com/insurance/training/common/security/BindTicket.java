package com.insurance.training.common.security;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 短期票据（bindTicket）。
 *
 * <p>未绑定微信用户拿到 openid 后，需要一个不依赖登录态的短期凭据来调用手机号绑定接口。
 * 票据是随机会话标识，配额与过期由 Redis 承载；这里只负责生成与摘要。
 */
public final class BindTicket {

    private final String token;

    private BindTicket(String token) {
        this.token = token;
    }

    public static BindTicket random() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return new BindTicket(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public String value() {
        return token;
    }

    /**
     * Redis key 使用摘要而非票据本身：避免 Redis 快照/日志泄露票据后可被直接重放。
     */
    public String redisKey() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "wx:bind:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "票据摘要算法不可用");
        }
    }

    public static String redisKeyOf(String ticket) {
        return new BindTicket(ticket).redisKey();
    }
}
