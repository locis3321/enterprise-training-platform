package com.insurance.training.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析：X-Forwarded-For 只能在受信代理来源时采信。
 *
 * <p>回归点：若不限制来源，攻击者每次带不同 XFF 即可绕过「账号+IP」维度的登录限流。
 */
class CurrentUserHolderClientIpTest {

    @Test
    @DisplayName("默认（无受信代理）→ 忽略 XFF，返回 remoteAddr")
    void ignoresForwardedHeaderWhenNoTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(CurrentUserHolder.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("直连来源伪造 XFF → 仍返回 remoteAddr（无法绕过限流）")
    void attackerCannotSpoofDifferentIps() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("203.0.113.9");
        first.addHeader("X-Forwarded-For", "10.0.0.1");

        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("203.0.113.9");
        second.addHeader("X-Forwarded-For", "10.0.0.2");

        // 伪造不同 XFF 不得让限流键变化
        assertThat(CurrentUserHolder.clientIp(first))
                .isEqualTo(CurrentUserHolder.clientIp(second));
    }

    @Test
    @DisplayName("无 XFF → 返回 remoteAddr")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.7");

        assertThat(CurrentUserHolder.clientIp(request)).isEqualTo("198.51.100.7");
    }
}
