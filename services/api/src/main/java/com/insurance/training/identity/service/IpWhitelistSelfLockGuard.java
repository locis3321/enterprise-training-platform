package com.insurance.training.identity.service;

import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.service.SystemConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 白名单与受信代理的联动校验，防止管理员把自己锁在系统外。
 *
 * <p>这两个配置共同决定「当前请求是谁」和「谁能进」，单独校验任一都挡不住锁死：
 * 保存白名单时要按**待保存的** trusted-proxies 重新解析当前 IP，再判断是否仍被放行。
 *
 * <p>校验发生在写入前；不通过直接 400，不写入。
 */
@Component
public class IpWhitelistSelfLockGuard implements SystemConfigService.ConfigValidator {

    private final SystemConfigService config;

    public IpWhitelistSelfLockGuard(SystemConfigService config) {
        this.config = config;
        config.addValidator(this);
    }

    @Override
    public String validate(SystemConfigKey key, String normalizedValue) {
        String requestIp = currentRequestIp();
        if (requestIp == null) {
            // 无请求上下文（如后台任务）时不适用；白名单语义只作用于管理端登录请求
            return null;
        }
        if (key == SystemConfigKey.TRUSTED_PROXIES) {
            return validateTrustedProxies(normalizedValue, requestIp);
        }
        if (key == SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST) {
            return validateWhitelist(normalizedValue, requestIp);
        }
        return null;
    }

    private String validateTrustedProxies(String newProxies, String requestIp) {
        List<String> whitelist = config.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST);
        if (whitelist.isEmpty()) {
            return null;
        }
        String resolved = resolveIp(parse(newProxies), currentRequest());
        if (resolved == null || !AdminIpWhitelist.matchesAny(whitelist, resolved)) {
            return "保存后当前网络将无法登录管理端（解析 IP " + resolved + " 不在白名单内）";
        }
        return null;
    }

    private String validateWhitelist(String newWhitelist, String ignored) {
        List<String> entries = parse(newWhitelist);
        if (entries.isEmpty()) {
            return null;
        }
        String resolved = resolveIp(parse(config.getString(SystemConfigKey.TRUSTED_PROXIES)), currentRequest());
        if (resolved == null || !AdminIpWhitelist.matchesAny(entries, resolved)) {
            return "保存后当前网络将无法登录管理端（当前 IP " + resolved + " 不在新白名单内）";
        }
        return null;
    }

    /** 用给定受信代理列表重新计算客户端 IP，与 CurrentUserHolder.clientIp 口径一致。 */
    private String resolveIp(List<String> trustedProxies, HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remoteAddr = request.getRemoteAddr();
        boolean trusted = trustedProxies.stream()
                .anyMatch(t -> t.equals(remoteAddr) || AdminIpWhitelist.matchesAny(List.of(t), remoteAddr));
        if (!trusted) {
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return remoteAddr;
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String currentRequestIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : CurrentUserHolder.clientIp(request);
    }

    private static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
