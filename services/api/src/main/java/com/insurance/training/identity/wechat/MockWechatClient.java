package com.insurance.training.identity.wechat;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 本地与测试环境的微信客户端替身。
 *
 * <p>仅在 {@code local}/{@code test} profile 生效。返回值由输入 code 确定性派生，
 * 便于写可重复的集成测试，但**不构成任何真实性证明**：手机号与 openid 都是模拟值。
 * 使用约束：code 必须符合 32 位十六进制（与真实格式一致）；手机号授权 code 前缀约定为
 * {@code phone-<11位手机号>}，便于本地联调时精确控制命中哪个员工档案。
 */
@Component
@Profile({"local", "test"})
public class MockWechatClient implements WechatClient {

    private static final Logger log = LoggerFactory.getLogger(MockWechatClient.class);
    /**
     * 真实 wx.login 的 code 为 32 位字母数字混合串（大小写敏感），
     * 早期误按纯十六进制校验会拒绝真实 code，这里放宽为「32 位可打印字符」。
     */
    private static final Pattern CODE32 = Pattern.compile("^[A-Za-z0-9_-]{32}$");
    private static final Pattern PHONE_CODE = Pattern.compile("^phone-(1[3-9]\\d{9})$");

    public MockWechatClient() {
        log.warn("已启用 MockWechatClient：微信登录与手机号均为模拟值，仅限本地/测试环境");
    }

    @Override
    public Session code2Session(String code) {
        if (code == null || !CODE32.matcher(code).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "微信登录 code 格式不正确");
        }
        return new Session("mock-openid-" + sha256(code).substring(0, 24), null);
    }

    @Override
    public String getPhoneNumber(String phoneCode) {
        if (phoneCode == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少手机号授权 code");
        }
        var matcher = PHONE_CODE.matcher(phoneCode);
        if (!matcher.matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号授权 code 格式不正确（本地 Mock 约定 phone-<手机号>）");
        }
        return matcher.group(1);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "摘要算法不可用");
        }
    }
}
