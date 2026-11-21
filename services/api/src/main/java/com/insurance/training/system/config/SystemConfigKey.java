package com.insurance.training.system.config;

import java.time.Duration;

/**
 * 可热改的系统配置项注册表。
 *
 * <p>这里是「有哪些可配置项、类型是什么、怎么校验、出厂默认来自哪个 property」的**唯一权威**。
 * 数据库 {@code sys_config} 只存覆盖值；行不存在时回退到本枚举声明的出厂默认（yml / 环境变量）。
 *
 * <p>**严禁**在此注册密钥类配置（AppSecret、JWT secret、数据库/Redis/MinIO 凭证）——按 D4 它们只能留在环境变量，
 * 由 {@code SystemConfigKeyTest} 自动守卫。
 */
public enum SystemConfigKey {

    WECHAT_APPID("wechat.appid", ValueType.STRING, "小程序", "小程序 AppID",
            "app.wechat.appid", null),

    ADMIN_LOGIN_IP_WHITELIST("security.admin-login-ip-whitelist", ValueType.CSV, "安全",
            "管理端登录 IP 白名单", null,
            "空 = 不限制；支持精确 IP 与 CIDR，逗号分隔。仅作用于管理端登录接口"),

    TRUSTED_PROXIES("security.trusted-proxies", ValueType.CSV, "安全",
            "受信反向代理地址", "app.security.trusted-proxies",
            "只有来自这些地址的请求，其 X-Forwarded-For 才被采信"),

    LOGIN_MAX_FAILURES("login.max-failures", ValueType.INT, "登录",
            "登录失败锁定阈值", "app.login.max-failures", "1-100 次"),

    LOGIN_FAILURE_WINDOW("login.failure-window", ValueType.DURATION, "登录",
            "登录失败计数窗口", "app.login.failure-window", "ISO-8601，如 PT5M，不小于 PT1M"),

    LEARNING_HEARTBEAT_MIN_INTERVAL("learning.heartbeat-min-interval", ValueType.DURATION, "学习",
            "学习心跳最小间隔", "app.learning.heartbeat-min-interval", "ISO-8601，如 PT5S，不小于 PT1S"),

    /**
     * 隐私协议 PDF 的 MinIO objectKey。
     *
     * <p>值是对象键而非可直接编辑的文本，因此标记 {@code uploadOnly}：不出现在通用设置列表，
     * 由 {@code /admin/settings/legal/privacy-policy} 专用上传接口维护，避免管理员手填键名把协议指丢。
     */
    LEGAL_PRIVACY_POLICY("legal.privacy-policy", ValueType.STRING, "法务",
            "隐私协议 PDF", null, "由管理端上传维护，值为对象存储键", true);

    /** 值类型与解析/校验规则。解析失败抛 IllegalArgumentException，由调用方转 400。 */
    public enum ValueType {
        STRING {
            @Override
            public String normalize(String raw) {
                return raw == null ? null : raw.trim();
            }

            @Override
            public void validate(String value) {
                if (value != null && value.length() > 2048) {
                    throw new IllegalArgumentException("配置值长度超过 2048 字符");
                }
            }
        },
        INT {
            @Override
            public String normalize(String raw) {
                int v = Integer.parseInt(raw == null ? "" : raw.trim());
                if (v < 1 || v > 100) {
                    throw new IllegalArgumentException("取值需在 1-100 之间");
                }
                return String.valueOf(v);
            }

            @Override
            public void validate(String value) {
                normalize(value);
            }
        },
        DURATION {
            @Override
            public String normalize(String raw) {
                Duration d = Duration.parse(raw == null ? "" : raw.trim());
                if (d.isZero() || d.isNegative()) {
                    throw new IllegalArgumentException("时长必须为正");
                }
                return d.toString();
            }

            @Override
            public void validate(String value) {
                Duration d = Duration.parse(normalize(value));
                if (d.compareTo(Duration.ofSeconds(1)) < 0) {
                    throw new IllegalArgumentException("时长不能小于 PT1S");
                }
            }
        },
        CSV {
            @Override
            public String normalize(String raw) {
                if (raw == null) {
                    return "";
                }
                return java.util.Arrays.stream(raw.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.joining(","));
            }

            @Override
            public void validate(String value) {
                String normalized = normalize(value);
                if (normalized.length() > 2048) {
                    throw new IllegalArgumentException("配置值长度超过 2048 字符");
                }
                for (String item : normalized.split(",")) {
                    if (!item.isEmpty() && !looksLikeIpOrCidr(item)) {
                        throw new IllegalArgumentException("不是合法的 IP 或 CIDR：" + item);
                    }
                }
            }
        };

        public abstract String normalize(String raw);

        public abstract void validate(String value);
    }

    private final String key;
    private final ValueType type;
    private final String group;
    private final String label;
    private final String defaultProperty;
    private final String description;
    private final boolean uploadOnly;

    SystemConfigKey(String key, ValueType type, String group, String label,
                    String defaultProperty, String description) {
        this(key, type, group, label, defaultProperty, description, false);
    }

    SystemConfigKey(String key, ValueType type, String group, String label,
                    String defaultProperty, String description, boolean uploadOnly) {
        this.key = key;
        this.type = type;
        this.group = group;
        this.label = label;
        this.defaultProperty = defaultProperty;
        this.description = description;
        this.uploadOnly = uploadOnly;
    }

    /** true 表示由专用上传接口维护，不出现在通用文本设置列表。 */
    public boolean uploadOnly() {
        return uploadOnly;
    }

    public String key() {
        return key;
    }

    public ValueType type() {
        return type;
    }

    public String group() {
        return group;
    }

    public String label() {
        return label;
    }

    /** 出厂默认对应的 Spring property；null 表示没有默认值（如白名单默认不限制）。 */
    public String defaultProperty() {
        return defaultProperty;
    }

    public String description() {
        return description;
    }

    public static SystemConfigKey of(String key) {
        for (SystemConfigKey k : values()) {
            if (k.key.equals(key)) {
                return k;
            }
        }
        return null;
    }

    /** 宽松校验：4 段 IPv4、IPv4/CIDR 或 IPv6 字面量。 */
    private static boolean looksLikeIpOrCidr(String item) {
        String host = item;
        int slash = item.indexOf('/');
        if (slash > 0) {
            host = item.substring(0, slash);
            String prefix = item.substring(slash + 1);
            if (!prefix.matches("\\d{1,3}")) {
                return false;
            }
            int bits = Integer.parseInt(prefix);
            if (host.contains(":")) {
                if (bits > 128) {
                    return false;
                }
            }
            else if (bits > 32) {
                return false;
            }
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.contains(":") || "localhost".equals(host);
    }
}
