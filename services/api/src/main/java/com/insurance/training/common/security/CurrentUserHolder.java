package com.insurance.training.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前请求上下文持有者。
 *
 * <p>实现为 request attribute 而不是 ThreadLocal，避免异步/线程池场景下
 * 忘记清理导致的身份串号。
 */
public final class CurrentUserHolder {

    public static final String ATTRIBUTE = "it.currentUser";

    /**
     * 受信反向代理地址（逗号分隔）。只有来自这些地址的请求，其 X-Forwarded-For
     * 才被采信；否则一律用 {@code remoteAddr}。
     *
     * <p>为什么必须限制：XFF 是客户端可自由伪造的请求头。若无条件采信，
     * 攻击者每次带上不同的 XFF 就能绕过「账号+IP」维度的登录限流，无限爆破密码。
     * 默认空 = 不信任任何代理（直连场景最安全）。
     */
    private static volatile String[] trustedProxies = new String[0];

    private CurrentUserHolder() {
    }

    /** 由 Spring 在启动时注入受信代理配置（首帧值）。 */
    @Component
    static class TrustedProxyConfigurer {
        @Autowired
        void configure(@Value("${app.security.trusted-proxies:}") String trustedProxies) {
            setTrustedProxies(trustedProxies);
        }
    }

    /** 配置热加载时刷新受信代理列表；空值 = 不信任任何代理（P1-2 语义不回退）。 */
    public static void setTrustedProxies(String csv) {
        if (csv == null || csv.isBlank()) {
            CurrentUserHolder.trustedProxies = new String[0];
            return;
        }
        CurrentUserHolder.trustedProxies = java.util.Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    public static void set(CurrentUser user) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new IllegalStateException("没有请求上下文，无法写入认证主体");
        }
        attributes.setAttribute(ATTRIBUTE, user, RequestAttributes.SCOPE_REQUEST);
    }

    /** 返回当前主体；未认证时返回 {@code null}。 */
    public static CurrentUser get() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return (CurrentUser) attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }

    /** 返回当前主体；未认证时抛异常，供已受保护接口使用。 */
    public static CurrentUser require() {
        CurrentUser user = get();
        if (user == null) {
            throw new IllegalStateException("请求未经认证，不应进入受保护接口");
        }
        return user;
    }

    /**
     * 客户端 IP，用于限流与审计。
     *
     * <p>只有请求的直连来源在受信代理列表中时，才采信 X-Forwarded-For；
     * 否则使用 {@code remoteAddr}，防止通过伪造请求头绕过限流。
     */
    public static String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return remoteAddr;
    }

    private static boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        for (String trusted : trustedProxies) {
            if (trusted.equals(remoteAddr) || cidrContains(trusted, remoteAddr)) {
                return true;
            }
        }
        return false;
    }

    /** 受信代理支持 CIDR（如 10.0.0.0/8）。非法条目视为不匹配。 */
    private static boolean cidrContains(String cidr, String ip) {
        int slash = cidr.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String network = cidr.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        }
        catch (NumberFormatException ex) {
            return false;
        }
        byte[] net = ipToBytes(network);
        byte[] addr = ipToBytes(ip);
        if (net == null || addr == null || net.length != addr.length || prefix > net.length * 8) {
            return false;
        }
        for (int i = 0; i < prefix; i++) {
            int bit = 0x80 >> (i % 8);
            if ((net[i / 8] & bit) != (addr[i / 8] & bit)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ipToBytes(String ip) {
        byte[] out = new byte[4];
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return out;
    }
}
