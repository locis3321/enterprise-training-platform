package com.insurance.training.identity.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 登录白名单作用面、CIDR 判定与防自锁联动。 */
class AdminIpWhitelistTest {

    private final SystemConfigService config = mock(SystemConfigService.class);

    @Test
    @DisplayName("白名单为空 = 不限制")
    void emptyWhitelistAllowsAll() {
        when(config.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST)).thenReturn(List.of());
        var whitelist = new AdminIpWhitelist(config);

        assertThatCode(() -> whitelist.assertAllowed("203.0.113.9")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("精确 IP 命中放行，未命中 403")
    void exactIp() {
        when(config.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST))
                .thenReturn(List.of("10.0.0.5"));
        var whitelist = new AdminIpWhitelist(config);

        assertThatCode(() -> whitelist.assertAllowed("10.0.0.5")).doesNotThrowAnyException();
        assertThatThrownBy(() -> whitelist.assertAllowed("10.0.0.6"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("CIDR 段内放行、段外拒绝")
    void cidr() {
        when(config.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST))
                .thenReturn(List.of("10.0.0.0/8", "192.168.1.0/24"));
        var whitelist = new AdminIpWhitelist(config);

        assertThatCode(() -> whitelist.assertAllowed("10.255.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> whitelist.assertAllowed("192.168.1.77")).doesNotThrowAnyException();
        assertThatThrownBy(() -> whitelist.assertAllowed("192.168.2.1"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> whitelist.assertAllowed("11.0.0.1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("防自锁：保存不含当前 IP 的白名单被拒绝")
    void selfLockRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(config.getString(SystemConfigKey.TRUSTED_PROXIES)).thenReturn(null);
            when(config.getCsv(eq(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST))).thenReturn(List.of());

            var guard = new IpWhitelistSelfLockGuard(config);
            String reason = guard.validate(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST, "10.0.0.5");

            assertThat(reason).contains("无法登录管理端").contains("127.0.0.1");

            // 含当前 IP 时放行
            assertThat(guard.validate(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST, "127.0.0.1,10.0.0.5"))
                    .isNull();
            // 空 = 不限制，放行
            assertThat(guard.validate(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST, "")).isNull();
        }
        finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("防自锁：改 trusted-proxies 后按新代理重解析仍须在白名单内")
    void trustedProxyChangeChecked() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(config.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST))
                    .thenReturn(List.of("10.0.0.9"));

            var guard = new IpWhitelistSelfLockGuard(config);
            // 不信任代理 → 解析为 127.0.0.1，不在白名单 → 拒绝
            assertThat(guard.validate(SystemConfigKey.TRUSTED_PROXIES, "")).contains("无法登录管理端");
            // 信任 127.0.0.1 → 解析为 XFF 10.0.0.9，在白名单 → 放行
            assertThat(guard.validate(SystemConfigKey.TRUSTED_PROXIES, "127.0.0.1")).isNull();
        }
        finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("非 IP 类配置不触发防自锁校验")
    void otherKeysUnaffected() {
        var guard = new IpWhitelistSelfLockGuard(config);
        assertThat(guard.validate(SystemConfigKey.LOGIN_MAX_FAILURES, "3")).isNull();
    }
}
