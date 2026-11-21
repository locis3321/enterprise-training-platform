-- ============================================================================
-- V2：身份、租户、员工与审计（Task 02）
--
-- 约定（见 docs/DEVELOPMENT.md 与 V1 注释）：
--   * 时间统一 DATETIME，存 Asia/Shanghai 本地时间；应用层与 API 输出 +08:00
--   * 所有表含 created_at / updated_at；企业相关表必须可追溯到保险公司
--   * 逻辑删除统一 deleted TINYINT（0 未删 / 1 已删）
--   * 状态统一 status TINYINT（1 启用 / 0 停用），不使用 NULL 表达未知状态
--   * 保单实体不建表：培训平台不关联具体保险业务（2026-11-09 用户决定）
-- ============================================================================

CREATE TABLE insurance_company (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(128) NOT NULL COMMENT '保险公司名称',
    code            VARCHAR(64)  NOT NULL COMMENT '业务编码，导入与对接使用',
    contact_name    VARCHAR(64)           DEFAULT NULL,
    contact_phone   VARCHAR(32)           DEFAULT NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_code (code),
    KEY idx_company_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '保险公司';

CREATE TABLE enterprise (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    insurance_company_id  BIGINT       NOT NULL COMMENT '归属保险公司，数据范围唯一来源',
    name                  VARCHAR(128) NOT NULL COMMENT '投保企业名称',
    code                  VARCHAR(64)  NOT NULL COMMENT '企业编码，Excel 导入按此匹配',
    contact_name          VARCHAR(64)           DEFAULT NULL,
    contact_phone         VARCHAR(32)           DEFAULT NULL,
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enterprise_company_code (insurance_company_id, code),
    KEY idx_enterprise_company (insurance_company_id),
    CONSTRAINT fk_enterprise_company FOREIGN KEY (insurance_company_id) REFERENCES insurance_company (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '投保企业';

CREATE TABLE employee (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    insurance_company_id  BIGINT       NOT NULL COMMENT '冗余归属，写入时由企业继承并校验一致',
    enterprise_id         BIGINT       NOT NULL,
    name                  VARCHAR(64)  NOT NULL,
    phone                 VARCHAR(20)  NOT NULL COMMENT '手机号，仅存服务端取得的号码',
    id_card_no            VARCHAR(32)           DEFAULT NULL COMMENT '可选；接口不返回明文',
    status                TINYINT      NOT NULL DEFAULT 1 COMMENT '1 在职启用 / 0 停用',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_enterprise_phone (enterprise_id, phone),
    KEY idx_employee_phone (phone),
    KEY idx_employee_company (insurance_company_id),
    KEY idx_employee_enterprise (enterprise_id),
    CONSTRAINT fk_employee_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '员工档案';

CREATE TABLE wechat_identity (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    appid         VARCHAR(64) NOT NULL,
    openid        VARCHAR(64) NOT NULL,
    employee_id   BIGINT      NOT NULL,
    phone         VARCHAR(20) NOT NULL COMMENT '绑定时的手机号快照，用于审计与排查',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '1 有效 / 0 解绑',
    bound_at      DATETIME    NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wechat_appid_openid (appid, openid),
    UNIQUE KEY uk_wechat_employee_appid (employee_id, appid),
    KEY idx_wechat_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '微信身份绑定';

CREATE TABLE role (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    code             VARCHAR(64) NOT NULL,
    name             VARCHAR(64) NOT NULL,
    data_scope_type  VARCHAR(32) NOT NULL COMMENT 'ALL / INSURANCE_COMPANY / ENTERPRISE',
    description      VARCHAR(255)         DEFAULT NULL,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色与数据范围定义';

CREATE TABLE admin_user (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    username              VARCHAR(64)  NOT NULL,
    password_hash         VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，禁止存明文',
    display_name          VARCHAR(64)  NOT NULL,
    role_code             VARCHAR(64)  NOT NULL,
    insurance_company_id  BIGINT                DEFAULT NULL COMMENT 'INSURANCE_COMPANY 范围时必填',
    enterprise_id         BIGINT                DEFAULT NULL COMMENT 'ENTERPRISE 范围时必填',
    status                TINYINT      NOT NULL DEFAULT 1,
    last_login_at         DATETIME              DEFAULT NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username),
    KEY idx_admin_role (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理端账号';

CREATE TABLE operation_log (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    actor_type   VARCHAR(16) NOT NULL COMMENT 'ADMIN / EMPLOYEE / SYSTEM',
    actor_id     BIGINT               DEFAULT NULL,
    action       VARCHAR(64) NOT NULL,
    target_type  VARCHAR(64)          DEFAULT NULL,
    target_id    BIGINT               DEFAULT NULL,
    detail       VARCHAR(2000)        DEFAULT NULL COMMENT '结构化摘要，禁止写入手机号/身份证明文',
    ip           VARCHAR(64)          DEFAULT NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_log_actor (actor_type, actor_id),
    KEY idx_log_action_time (action, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '操作审计日志';

-- 角色种子（系统常量，不含任何账号口令）
INSERT INTO role (code, name, data_scope_type, description) VALUES
    ('SUPER_ADMIN', '平台超级管理员', 'ALL', '平台配置、租户管理、全局审计'),
    ('INSURANCE_ADMIN', '保险公司管理员', 'INSURANCE_COMPANY', '管理授权保险公司范围内的企业与培训'),
    ('ENTERPRISE_ADMIN', '企业管理员', 'ENTERPRISE', '查看本企业员工与培训情况');

-- 初始超级管理员（本地开发默认账号，用户 2026-11-09 决定采用内置方式）
-- 口令：Admin@12345（BCrypt cost 10）。生产 profile 启动守卫会强制要求修改。
INSERT INTO admin_user (username, password_hash, display_name, role_code, status) VALUES
    ('admin', '$2a$10$CsVzDSxzxkJMVk3E8LQlLe9FgjvQZ3OFzJWHwupdPzGuanncS8jw6', '平台超级管理员', 'SUPER_ADMIN', 1);
