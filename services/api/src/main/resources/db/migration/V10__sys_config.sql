-- ============================================================================
-- V10：系统配置覆盖值表
--
-- 设计见 docs/memory/tasks-2611-1123-49/T7-系统配置表与热加载基座.md
--   1. 只存「被显式覆盖过的配置」。行不存在 = 使用出厂默认（yml / 环境变量）。
--      因此本表不播种任何值：生产 WECHAT_APPID、TRUSTED_PROXIES 由环境变量提供，
--      SQL 播种具体值会在升级后覆盖 env，直接打断微信登录与 IP 解析。
--   2. 可配置项、类型、分组、校验规则是代码里的注册表（SystemConfigKey 枚举），
--      不落库，避免与代码漂移。
--   3. 「恢复出厂默认」= 物理删除该覆盖行。这是配置覆盖而非业务单据，
--      且每次变更都写 operation_log，因此不引入逻辑删除列。
-- ============================================================================

CREATE TABLE sys_config (
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    config_key           VARCHAR(128)  NOT NULL COMMENT '配置键，对应 SystemConfigKey 枚举',
    config_value         VARCHAR(2048) NOT NULL COMMENT '覆盖值；行存在即表示已覆盖',
    updated_by_admin_id  BIGINT        DEFAULT NULL COMMENT '最后修改人（admin_user.id）',
    created_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at           DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_config_key (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='系统配置覆盖值（行不存在=出厂默认，不播种）';
