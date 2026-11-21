package com.insurance.training.common.security;

import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.service.SystemConfigService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 配置刷新时把受信反向代理列表同步到 IP 解析器。
 *
 * <p>不直接把 {@code app.security.trusted-proxies} 的 yml 值当作最终来源：
 * 数据库覆盖优先，未覆盖时由 {@code SystemConfigService} 回退出厂默认，二者在此统一为一份值。
 */
@Component
public class TrustedProxyConfigListener implements SystemConfigService.ConfigRefreshListener {

    private final SystemConfigService config;

    public TrustedProxyConfigListener(SystemConfigService config) {
        this.config = config;
    }

    @PostConstruct
    void register() {
        config.addRefreshListener(this);
    }

    @Override
    public void onRefreshed(java.util.Map<String, String> snapshot, java.util.List<SystemConfigService.ConfigKeyView> keys) {
        String csv = null;
        for (SystemConfigService.ConfigKeyView view : keys) {
            if (view.key().equals(SystemConfigKey.TRUSTED_PROXIES.key())) {
                csv = view.value();
                break;
            }
        }
        CurrentUserHolder.setTrustedProxies(csv);
    }
}
