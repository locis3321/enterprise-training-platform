package com.insurance.training.identity.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.service.SystemConfigService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理端登录 IP 白名单。
 *
 * <p>作用面**仅限管理端登录接口**：不作用于小程序学员登录（移动网络 IP 多变），
 * 也不作用于已持 token 的后续管理请求。空 = 不限制。
 */
@Component
public class AdminIpWhitelist {

    private final SystemConfigService config;

    public AdminIpWhitelist(SystemConfigService config) {
        this.config = config;
    }

    /** 未配置白名单时直接放行；否则要求客户端 IP 命中精确 IP 或 CIDR。 */
    public void assertAllowed(String clientIp) {
        List<String> whitelist = config.getCsv(SystemConfigKey.ADMIN_LOGIN_IP_WHITELIST);
        if (whitelist.isEmpty()) {
            return;
        }
        if (!matchesAny(whitelist, clientIp)) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前网络不在管理端登录白名单内");
        }
    }

    public static boolean matchesAny(List<String> entries, String ip) {
        if (ip == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry.equals(ip) || cidrContains(entry, ip)) {
                return true;
            }
        }
        return false;
    }

    /** 仅支持 IPv4 CIDR；IPv6 精确匹配由 equals 覆盖。 */
    private static boolean cidrContains(String cidr, String ip) {
        int slash = cidr.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        }
        catch (NumberFormatException ex) {
            return false;
        }
        byte[] net = ipv4(cidr.substring(0, slash));
        byte[] addr = ipv4(ip);
        if (net == null || addr == null || prefix < 0 || prefix > 32) {
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

    private static byte[] ipv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
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
