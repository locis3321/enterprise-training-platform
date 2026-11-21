package com.insurance.training.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("unit-test-secret-key-with-32-bytes-min!!", Duration.ofHours(1));
        jwtService.init();
    }

    @Test
    @DisplayName("签发后可用同一密钥解析出管理端主体")
    void issueAndParseAdminToken() {
        String token = jwtService.issueAdminToken(7L, "INSURANCE_ADMIN",
                DataScopeType.INSURANCE_COMPANY, 3L, null, "王经理");

        CurrentUser user = jwtService.parse(token).orElseThrow();

        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.type()).isEqualTo(PrincipalType.ADMIN);
        assertThat(user.roleCode()).isEqualTo("INSURANCE_ADMIN");
        assertThat(user.scope()).isEqualTo(DataScopeType.INSURANCE_COMPANY);
        assertThat(user.companyId()).isEqualTo(3L);
        assertThat(user.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("被篡改的 token 解析为空，调用方据此返回 401")
    void tamperedTokenIsRejected() {
        String token = jwtService.issueEmployeeToken(11L, 1L, 2L, "张三");
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThat(jwtService.parse(tampered)).isEmpty();
    }

    @Test
    @DisplayName("其他密钥签发的 token 不可信")
    void tokenFromOtherSecretIsRejected() {
        JwtService other = new JwtService("another-secret-key-with-32-bytes-long!!", Duration.ofHours(1));
        other.init();
        String foreign = other.issueAdminToken(1L, "SUPER_ADMIN", DataScopeType.ALL, null, null, "x");

        assertThat(jwtService.parse(foreign)).isEmpty();
    }

    @Test
    @DisplayName("过期 token 解析为空")
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService("unit-test-secret-key-with-32-bytes-min!!", Duration.ofSeconds(-1));
        shortLived.init();
        String token = shortLived.issueEmployeeToken(11L, 1L, 2L, "张三");

        assertThat(jwtService.parse(token)).isEmpty();
    }

    @Test
    @DisplayName("密钥缺失或过短时启动失败，不静默使用弱密钥")
    void weakSecretFailsFast() {
        JwtService weak = new JwtService("too-short", Duration.ofHours(1));

        assertThatThrownBy(weak::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }
}
