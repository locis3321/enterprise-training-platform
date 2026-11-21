package com.insurance.training.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.entity.SysConfig;
import com.insurance.training.system.mapper.SysConfigMapper;
import com.insurance.training.system.vo.SystemSettingVo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置读取与热加载。
 *
 * <p>快照是不可变 Map，整体替换（volatile 写），读取无锁、无 IO。
 * 生效时效：写入节点在事务提交后立即刷新，其他节点由 30 秒定时任务全量刷新——
 * **不宣称集群瞬时生效**。
 *
 * <p>刷新失败时保留上一份可用快照并记录 ERROR：清空会让 IP 白名单静默失效，属安全回退。
 * 失败状态通过 {@link #listSettings()} 暴露，不被藏起来。
 */
@Service
public class SystemConfigService {

    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);

    private final SysConfigMapper mapper;
    private final Environment environment;
    private final AuditService auditService;
    /** 注册式而非构造注入：校验器本身依赖本服务，构造注入会形成循环依赖。 */
    private final List<ConfigValidator> validators = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile Map<String, String> snapshot = Map.of();
    private volatile LocalDateTime lastRefreshAt;
    private volatile String lastRefreshError;

    public SystemConfigService(SysConfigMapper mapper, Environment environment,
                               AuditService auditService) {
        this.mapper = mapper;
        this.environment = environment;
        this.auditService = auditService;
    }

    public void addValidator(ConfigValidator validator) {
        validators.add(validator);
    }

    /** 刷新成功后回调（如把 trusted-proxies 同步到 IP 解析器）。 */
    public interface ConfigRefreshListener {
        void onRefreshed(Map<String, String> snapshot, List<ConfigKeyView> keys);
    }

    /** 供监听器读取「当前生效值」的最小视图。 */
    public record ConfigKeyView(String key, String value) {
    }

    private final List<ConfigRefreshListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addRefreshListener(ConfigRefreshListener listener) {
        listeners.add(listener);
    }

    /** 运营侧可在保存前追加校验（如白名单防自锁），由 T8 注册实现。 */
    public interface ConfigValidator {
        /** 返回非空表示拒绝原因；null 表示通过。 */
        String validate(SystemConfigKey key, String normalizedValue);
    }

    @PostConstruct
    public void loadAtStartup() {
        refresh();
    }

    /** 30 秒全量刷新；写后另有 afterCommit 立即刷新本节点。 */
    @Scheduled(fixedDelay = 30_000L)
    public void scheduledRefresh() {
        refresh();
    }

    public synchronized void refresh() {
        try {
            List<SysConfig> rows = mapper.selectList(Wrappers.<SysConfig>lambdaQuery());
            Map<String, String> next = new LinkedHashMap<>();
            for (SysConfig row : rows) {
                next.put(row.getConfigKey(), row.getConfigValue());
            }
            snapshot = Collections.unmodifiableMap(next);
            lastRefreshAt = LocalDateTime.now();
            lastRefreshError = null;
            notifyListeners();
        }
        catch (RuntimeException ex) {
            // 保留上一份快照：清空会让白名单等安全配置静默失效
            lastRefreshError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("系统配置刷新失败，继续使用上一份快照（key 数={}）", snapshot.size(), ex);
        }
    }

    // ---------- 读取 ----------

    public String getString(SystemConfigKey key) {
        String overridden = snapshot.get(key.key());
        if (overridden != null) {
            return overridden;
        }
        return defaultOf(key);
    }

    public int getInt(SystemConfigKey key) {
        return Integer.parseInt(getString(key));
    }

    public Duration getDuration(SystemConfigKey key) {
        return Duration.parse(getString(key));
    }

    /** 逗号分隔列表；未配置或为空时返回空列表（语义：不限制）。 */
    public List<String> getCsv(SystemConfigKey key) {
        String value = getString(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 当前是否被数据库覆盖（否则来自出厂默认）。 */
    public boolean isOverridden(SystemConfigKey key) {
        return snapshot.containsKey(key.key());
    }

    private String defaultOf(SystemConfigKey key) {
        String property = key.defaultProperty();
        if (property == null) {
            return null;
        }
        return environment.getProperty(property);
    }

    // ---------- 管理接口 ----------

    public List<SystemSettingVo> listSettings() {
        Map<String, SysConfig> rows = mapper.selectList(Wrappers.<SysConfig>lambdaQuery()).stream()
                .collect(java.util.stream.Collectors.toMap(SysConfig::getConfigKey, r -> r, (a, b) -> a));
        List<SystemSettingVo> result = new ArrayList<>();
        for (SystemConfigKey key : SystemConfigKey.values()) {
            if (key.uploadOnly()) {
                // 由专用上传接口维护，避免在通用文本框里被手填成无效对象键
                continue;
            }
            SysConfig row = rows.get(key.key());
            String effective = row != null ? row.getConfigValue() : defaultOf(key);
            result.add(new SystemSettingVo(key.key(), key.group(), key.label(), key.description(),
                    key.type().name(), effective, row != null ? "DATABASE" : "DEFAULT",
                    defaultOf(key),
                    row == null ? null : row.getUpdatedAt(),
                    row == null ? null : row.getUpdatedByAdminId()));
        }
        return result;
    }

    @Transactional
    public SystemSettingVo update(CurrentUser actor, String keyName, String rawValue, String ip) {
        DataScopeGuard.requireSuperAdmin();
        SystemConfigKey key = SystemConfigKey.of(keyName);
        if (key == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未注册的配置项：" + keyName);
        }
        String normalized = normalizeOrBadRequest(key, rawValue);
        String old = effectiveOf(key);
        for (ConfigValidator validator : validators) {
            String reason = validator.validate(key, normalized);
            if (reason != null) {
                throw new BizException(ErrorCode.BAD_REQUEST, reason);
            }
        }

        SysConfig row = mapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key.key()));
        if (row == null) {
            row = new SysConfig();
            row.setConfigKey(key.key());
            row.setConfigValue(normalized);
            row.setUpdatedByAdminId(actor.id());
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            mapper.insert(row);
        }
        else {
            row.setConfigValue(normalized);
            row.setUpdatedByAdminId(actor.id());
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(row);
        }
        refreshAfterCommit();
        auditService.record(actor, "SYS_CONFIG_UPDATED", "sys_config", row.getId(),
                "key=" + key.key() + ", old=" + old + ", new=" + normalized, ip);
        return listSettings().stream().filter(v -> v.key().equals(key.key())).findFirst().orElseThrow();
    }

    /**
     * 写入由上传接口管理的配置值（如隐私协议对象键）。
     *
     * <p>与 {@link #update} 分开：上传值不是用户输入的文本，无需类型/范围校验链，
     * 但仍写审计并在提交后刷新快照，保证与手工改配置同一生效路径。
     */
    @Transactional
    public void setManagedValue(CurrentUser actor, SystemConfigKey key, String value, String ip) {
        DataScopeGuard.requireSuperAdmin();
        SysConfig row = mapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key.key()));
        String old = row == null ? null : row.getConfigValue();
        if (row == null) {
            row = new SysConfig();
            row.setConfigKey(key.key());
            row.setConfigValue(value);
            row.setUpdatedByAdminId(actor.id());
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            mapper.insert(row);
        }
        else {
            row.setConfigValue(value);
            row.setUpdatedByAdminId(actor.id());
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(row);
        }
        refreshAfterCommit();
        auditService.record(actor, "SYS_CONFIG_UPDATED", "sys_config", row.getId(),
                "key=" + key.key() + ", old=" + old + ", new=" + value, ip);
    }

    /** 读取覆盖行的更新时间；未设置返回 null。 */
    public LocalDateTime overriddenAt(SystemConfigKey key) {
        SysConfig row = mapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key.key()));
        return row == null ? null : row.getUpdatedAt();
    }

    @Transactional
    public void reset(CurrentUser actor, String keyName, String ip) {
        DataScopeGuard.requireSuperAdmin();
        SystemConfigKey key = SystemConfigKey.of(keyName);
        if (key == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未注册的配置项：" + keyName);
        }
        SysConfig row = mapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key.key()));
        if (row == null) {
            // 已经是出厂默认，幂等返回，不制造无用审计
            return;
        }
        String old = row.getConfigValue();
        mapper.deleteById(row.getId());
        refreshAfterCommit();
        auditService.record(actor, "SYS_CONFIG_RESET", "sys_config", row.getId(),
                "key=" + key.key() + ", old=" + old, ip);
    }

    private String effectiveOf(SystemConfigKey key) {
        SysConfig row = mapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key.key()));
        return row != null ? row.getConfigValue() : defaultOf(key);
    }

    private String normalizeOrBadRequest(SystemConfigKey key, String rawValue) {
        try {
            String normalized = key.type().normalize(rawValue);
            key.type().validate(normalized);
            return normalized == null ? "" : normalized;
        }
        catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "配置值不合法（" + key.type().name() + "）：" + ex.getMessage());
        }
    }

    /** 写后立即刷新本节点；无事务时同步刷新。 */
    private void refreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh();
                }
            });
        }
        else {
            refresh();
        }
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) {
            return;
        }
        List<ConfigKeyView> effective = new ArrayList<>();
        for (SystemConfigKey key : SystemConfigKey.values()) {
            String value = snapshot.get(key.key());
            effective.add(new ConfigKeyView(key.key(), value != null ? value : defaultOf(key)));
        }
        for (ConfigRefreshListener listener : listeners) {
            try {
                listener.onRefreshed(snapshot, effective);
            }
            catch (RuntimeException ex) {
                log.error("配置刷新监听器执行失败：{}", listener.getClass().getSimpleName(), ex);
            }
        }
    }

    public LocalDateTime lastRefreshAt() {
        return lastRefreshAt;
    }

    public String lastRefreshError() {
        return lastRefreshError;
    }
}
