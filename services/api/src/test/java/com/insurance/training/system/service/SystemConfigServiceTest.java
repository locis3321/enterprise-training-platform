package com.insurance.training.system.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.entity.SysConfig;
import com.insurance.training.system.mapper.SysConfigMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 配置注册表、回退与刷新语义。 */
class SystemConfigServiceTest {

    private final SysConfigMapper mapper = mock(SysConfigMapper.class);
    private final Environment environment = mock(Environment.class);
    private final AuditService auditService = mock(AuditService.class);

    private SystemConfigService service(SystemConfigService.ConfigValidator... validators) {
        SystemConfigService svc = new SystemConfigService(mapper, environment, auditService);
        for (SystemConfigService.ConfigValidator v : validators) {
            svc.addValidator(v);
        }
        return svc;
    }

    private SysConfig row(String key, String value) {
        SysConfig r = new SysConfig();
        r.setId(1L);
        r.setConfigKey(key);
        r.setConfigValue(value);
        return r;
    }

    @Test
    @DisplayName("注册表不含任何密钥类配置项")
    void registryHasNoSecrets() {
        for (SystemConfigKey key : SystemConfigKey.values()) {
            String all = (key.key() + " " + key.defaultProperty()).toLowerCase();
            assertThat(all).doesNotContain("secret");
            assertThat(all).doesNotContain("password");
            assertThat(all).doesNotContain("access-key");
            assertThat(all).doesNotContain("accesskey");
        }
    }

    @Test
    @DisplayName("未覆盖时回退到出厂默认（Environment），并标注来源 DEFAULT")
    void fallsBackToDefault() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(environment.getProperty("app.login.max-failures")).thenReturn("5");
        when(environment.getProperty("app.learning.heartbeat-min-interval")).thenReturn("PT5S");
        when(environment.getProperty("app.login.failure-window")).thenReturn("PT5M");

        SystemConfigService svc = service();
        svc.refresh();

        assertThat(svc.getInt(SystemConfigKey.LOGIN_MAX_FAILURES)).isEqualTo(5);
        assertThat(svc.getDuration(SystemConfigKey.LEARNING_HEARTBEAT_MIN_INTERVAL))
                .isEqualTo(Duration.ofSeconds(5));
        assertThat(svc.isOverridden(SystemConfigKey.LOGIN_MAX_FAILURES)).isFalse();
        var settings = svc.listSettings();
        var login = settings.stream()
                .filter(s -> s.key().equals("login.max-failures")).findFirst().orElseThrow();
        assertThat(login.source()).isEqualTo("DEFAULT");
        assertThat(login.effectiveValue()).isEqualTo("5");
    }

    @Test
    @DisplayName("已覆盖时优先用数据库值，并标注来源 DATABASE")
    void overrideWins() {
        when(mapper.selectList(any())).thenReturn(List.of(row("login.max-failures", "3")));

        SystemConfigService svc = service();
        svc.refresh();

        assertThat(svc.getInt(SystemConfigKey.LOGIN_MAX_FAILURES)).isEqualTo(3);
        assertThat(svc.isOverridden(SystemConfigKey.LOGIN_MAX_FAILURES)).isTrue();
        assertThat(svc.listSettings().stream()
                .filter(s -> s.key().equals("login.max-failures")).findFirst().orElseThrow().source())
                .isEqualTo("DATABASE");
    }

    @Test
    @DisplayName("刷新失败保留上一份快照，白名单不失效且错误可见")
    void refreshFailureKeepsLastSnapshot() {
        when(mapper.selectList(any()))
                .thenReturn(List.of(row("security.admin-login-ip-whitelist", "10.0.0.1")))
                .thenThrow(new IllegalStateException("db down"));

        SystemConfigService svc = service();
        svc.refresh();
        List<String> before = svc.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST);
        svc.refresh();

        assertThat(before).containsExactly("10.0.0.1");
        assertThat(svc.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST)).containsExactly("10.0.0.1");
        assertThat(svc.lastRefreshError()).contains("db down");
        assertThat(svc.lastRefreshAt()).isNotNull();
    }

    @Test
    @DisplayName("类型校验：INT 非数字/越界、DURATION 非法、CSV 非法条目一律拒绝")
    void typeValidation() {
        assertThatThrownBy(() -> SystemConfigKey.ValueType.INT.normalize("abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SystemConfigKey.ValueType.INT.normalize("0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SystemConfigKey.ValueType.INT.normalize("101"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SystemConfigKey.ValueType.DURATION.normalize("5分钟"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SystemConfigKey.ValueType.CSV.validate("10.0.0.1,not-an-ip"))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatCode(
                () -> SystemConfigKey.ValueType.CSV.validate("10.0.0.1, 10.0.0.0/8"))
                .doesNotThrowAnyException();
        assertThat(SystemConfigKey.ValueType.CSV.normalize(" 10.0.0.1 , 10.0.0.2 "))
                .isEqualTo("10.0.0.1,10.0.0.2");
        assertThat(SystemConfigKey.ValueType.INT.normalize(" 7 ")).isEqualTo("7");
    }

    @Test
    @DisplayName("未注册 key 拒绝；值超长拒绝")
    void unknownKeyAndTooLong() {
        when(mapper.selectList(any())).thenReturn(List.of());
        SystemConfigService svc = service();

        // 未注册 key 在 of() 层判定，这里直接验枚举查找
        assertThat(SystemConfigKey.of("page.max-size")).isNull();
        assertThatThrownBy(() -> SystemConfigKey.ValueType.STRING.validate("x".repeat(2049)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048");
    }
}
