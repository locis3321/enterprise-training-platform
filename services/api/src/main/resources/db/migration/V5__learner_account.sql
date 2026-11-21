-- ============================================================================
-- V5：学员账号（手机号+密码登录）
--
-- 数据模型要点（见 docs/memory/tasks-2611-1017-57/T1-学员账号与密码登录.md）：
--   1. 学员账号以**手机号全局唯一**，独立于企业关联。
--      同一手机号可在多个企业各有 employee 记录（uk_employee_enterprise_phone 是
--      (enterprise_id, phone)，非全局唯一）；这些记录属同一学员的多条企业关联。
--      用户决策：小程序端不关心员工属于几个企业，学员本质只有一条信息。
--   2. 不建账号-员工关联表，按 phone 派生关联。
--      理由：关联表会引入第二处可写来源，与 employee.phone 双写必然漂移。
--      代价：员工改手机号时账号需同步迁移（在 Service 层处理，见 T1）。
--   3. password_hash 只存 BCrypt，禁止明文；长度 100 与 admin_user 一致。
--   4. 不改 employee 表：避免给既有表加凭证字段，防止「员工档案」与「登录凭证」
--      两个生命周期纠缠。账号可以独立存在（例如员工全部删除后账号保留）。
--   5. 初始密码规则 anzhi + 手机号后 6 位由 Service 生成，不在 DDL 表达。
--   6. 时间统一 DATETIME，存 Asia/Shanghai 本地时间（D-04），与既有表一致。
--   7. 不建首登强制改密标记：属 T7 上线前评估项，本轮不实现（见 E2E 残余风险）。
-- ============================================================================

CREATE TABLE learner_account (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    phone          VARCHAR(20)  NOT NULL COMMENT '账号标识，全局唯一；不在日志中输出明文',
    password_hash  VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，禁止存明文',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    last_login_at  DATETIME              DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learner_account_phone (phone),
    KEY idx_learner_account_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学员账号：手机号+密码登录的凭证主体';

-- 为既有员工补建账号（初始密码由应用层生成，此处无法计算 BCrypt，故留空由迁移后脚本处理）。
-- 说明：Flyway 迁移不生成密码哈希，避免把「弱口令规则」固化进 DDL 版本。
-- 既有员工在下次导入/管理员重置或首次通过密码登录前的引导流程中补齐。
-- 本轮采用显式做法：迁移只建表，账号补齐由 LearnerAccountService.ensureAccount 在
-- 导入、手工创建与重置路径上完成；已存在但无账号的员工在登录时会收到
-- 「账号尚未开通，请联系管理员重置密码」的明确提示，而不是静默失败。
