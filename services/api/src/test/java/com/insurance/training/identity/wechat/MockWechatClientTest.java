package com.insurance.training.identity.wechat;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockWechatClientTest {

    private final MockWechatClient client = new MockWechatClient();

    @Test
    @DisplayName("同一 code 得到稳定 openid，便于集成测试复现")
    void sameCodeYieldsSameOpenid() {
        String code = "0123456789abcdef0123456789abcdef";

        var first = client.code2Session(code);
        var second = client.code2Session(code);

        assertThat(first.openid()).isEqualTo(second.openid()).startsWith("mock-openid-");
    }

    @Test
    @DisplayName("真实 wx.login 的混合大小写 code 必须被接受")
    void realWorldMixedCaseCodeAccepted() {
        // 来自微信开发者工具 wx.login 的真实 code 形态
        var session = client.code2Session("0e3wrBGa1A4cMM0UJlJa1nZMis2wrBGb");

        assertThat(session.openid()).startsWith("mock-openid-");
    }

    @Test
    @DisplayName("非法 code 直接拒绝，不生成假 openid")
    void invalidCodeRejected() {
        assertThatThrownBy(() -> client.code2Session("bad"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("手机号授权 code 必须符合本地约定格式")
    void phoneCodeMustFollowLocalConvention() {
        assertThat(client.getPhoneNumber("phone-13800138000")).isEqualTo("13800138000");

        assertThatThrownBy(() -> client.getPhoneNumber("13800138000"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }
}
