-- ============================================================================
-- V4：培训包、任务分配与学习记录（Task 04）
--
-- 数据模型要点（见 docs/memory/tasks-2611-0921-49/T1-迁移V4.md）：
--   1. 七表：training_package / training_package_version / package_course
--      / training_task / training_assignment / learning_record / resource_open_log
--   2. 培训包发布生成不可变版本快照。package_course 指向具体的 course_version_id
--      （不是 course_id），因此课程改版不影响已发布的培训包与已分配任务。
--   3. training_assignment.package_version_id 冗余保存（ARCHITECTURE 第 4 节不变量）：
--      即使培训包后续发布新版本，已分配任务仍指向原版本。写入时强校验与 task 一致。
--   4. 计时基准唯一：last_heartbeat_at 只在 training_assignment 上。
--      资源级时钟下，一个章节 N 个视频交替心跳会把同一秒计入 N 次；
--      章节级时钟同理可跨章节交替刷倍数。只有任务级单一时钟能保证
--      「全任务累计有效时长 <= 真实经过时间」。learning_record 只存时长归属明细，
--      不含任何计时字段。
--   5. learning_record.resource_id NOT NULL：MySQL 唯一键对 NULL 不去重，
--      允许 NULL 会产生重复行。非视频资源不写 learning_record，
--      走 resource_open_log（append-only，仅记录首次打开）。
--   6. 不建 package_exam（用户决策：本任务不接考试，留 Task 05）。
--   7. 时间统一 DATETIME，存 Asia/Shanghai 本地时间（D-04）。
--      任务时间窗为左闭右开：start_at <= now < learn_deadline_at。
--   8. 状态沿用 V3 的 VARCHAR 枚举（多态生命周期），与 V2 的 TINYINT 启停语义不同；
--      此差异在 V3 已说明，不视为不一致。
--   9. training_package 与 course 同款归属模型：insurance_company_id 决定可见范围
--      （同公司共享），owner_enterprise_id 决定写权限归属（NULL=公司级，仅超管/公司
--      管理员可写）。缺少该列会使 T2 声明的 checkCourseWrite 规则无法落地——
--      该守卫在 ownerEnterpriseId 为 NULL 时对企业管理员一律 403。
--  10. 一致性约束（同树校验、版本必须 PUBLISHED、状态只进不退）在 Service 层强制，
--      不用 DB 触发器；外键只保证引用存在性。
-- ============================================================================

CREATE TABLE training_package (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    insurance_company_id  BIGINT       NOT NULL COMMENT '可见范围边界：同公司下所有企业可见',
    owner_enterprise_id   BIGINT                DEFAULT NULL COMMENT '写权限归属企业；NULL=公司级培训包（仅超管/公司管理员可写）',
    title                 VARCHAR(200) NOT NULL COMMENT '培训包名，列表与检索的权威来源',
    description           VARCHAR(2000)         DEFAULT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / OFFLINE',
    latest_version_no     INT          NOT NULL DEFAULT 0 COMMENT '最新版本号（含草稿）',
    published_version_no  INT                   DEFAULT NULL COMMENT '当前生效版本号；NULL=从未发布',
    created_by_admin_id   BIGINT                DEFAULT NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 / 1 已删',
    PRIMARY KEY (id),
    KEY idx_package_company_status (insurance_company_id, status, deleted),
    KEY idx_package_owner_enterprise (owner_enterprise_id),
    CONSTRAINT fk_package_company FOREIGN KEY (insurance_company_id) REFERENCES insurance_company (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '培训包（版本容器）';

CREATE TABLE training_package_version (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    package_id             BIGINT        NOT NULL,
    version_no             INT           NOT NULL,
    title                  VARCHAR(200)  NOT NULL COMMENT '版本快照标题，发布后不可变',
    description            VARCHAR(2000)          DEFAULT NULL,
    status                 VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    published_at           DATETIME               DEFAULT NULL,
    published_by_admin_id  BIGINT                 DEFAULT NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_package_version_no (package_id, version_no),
    KEY idx_package_version_status (package_id, status),
    CONSTRAINT fk_package_version_package FOREIGN KEY (package_id) REFERENCES training_package (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '培训包版本（发布快照，不可变）';

CREATE TABLE package_course (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    package_version_id BIGINT   NOT NULL,
    course_version_id  BIGINT   NOT NULL COMMENT '指向具体课程版本而非课程，保证快照不受课程改版影响',
    sort_no            INT      NOT NULL DEFAULT 1 COMMENT '包内课程排序，从 1 开始',
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_package_course (package_version_id, course_version_id),
    KEY idx_package_course_sort (package_version_id, sort_no),
    KEY idx_package_course_version (course_version_id),
    CONSTRAINT fk_package_course_pkg_version FOREIGN KEY (package_version_id) REFERENCES training_package_version (id),
    CONSTRAINT fk_package_course_course_version FOREIGN KEY (course_version_id) REFERENCES course_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '培训包版本-课程版本关联（仅 DRAFT 可增删）';

CREATE TABLE training_task (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    insurance_company_id  BIGINT       NOT NULL COMMENT '数据隔离边界',
    enterprise_id         BIGINT       NOT NULL COMMENT '任务下发到的目标企业',
    package_version_id    BIGINT       NOT NULL COMMENT '必须是 PUBLISHED 的培训包版本',
    title                 VARCHAR(200) NOT NULL,
    start_at              DATETIME     NOT NULL COMMENT '可学起点，左闭：now == start_at 可学',
    learn_deadline_at     DATETIME     NOT NULL COMMENT '学习截止，右开：now == deadline 即不可学',
    status                VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED' COMMENT 'DRAFT / PUBLISHED / CLOSED；MVP 创建即发布',
    created_by_admin_id   BIGINT                DEFAULT NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_enterprise_status (enterprise_id, status),
    KEY idx_task_company (insurance_company_id),
    KEY idx_task_package_version (package_version_id),
    CONSTRAINT fk_task_company FOREIGN KEY (insurance_company_id) REFERENCES insurance_company (id),
    CONSTRAINT fk_task_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise (id),
    CONSTRAINT fk_task_package_version FOREIGN KEY (package_version_id) REFERENCES training_package_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '培训任务（绑定具体培训包版本与时间窗）';

CREATE TABLE training_assignment (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    task_id            BIGINT      NOT NULL,
    employee_id        BIGINT      NOT NULL,
    package_version_id BIGINT      NOT NULL COMMENT '冗余快照引用，必须与 task 一致；培训包改版不影响本行',
    status             VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED / IN_PROGRESS / COMPLETED；只进不退',
    started_at         DATETIME             DEFAULT NULL COMMENT '首次心跳或首次资源 open 时写入',
    completed_at       DATETIME             DEFAULT NULL,
    last_heartbeat_at  DATETIME             DEFAULT NULL COMMENT '全任务唯一计时时钟；NULL=从未心跳，首次心跳计 0 秒',
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assignment_task_employee (task_id, employee_id),
    KEY idx_assignment_employee_status (employee_id, status),
    KEY idx_assignment_task_status (task_id, status),
    KEY idx_assignment_package_version (package_version_id),
    CONSTRAINT fk_assignment_task FOREIGN KEY (task_id) REFERENCES training_task (id),
    CONSTRAINT fk_assignment_employee FOREIGN KEY (employee_id) REFERENCES employee (id),
    CONSTRAINT fk_assignment_package_version FOREIGN KEY (package_version_id) REFERENCES training_package_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '员工任务分配（逐员工一行，含唯一计时时钟）';

CREATE TABLE learning_record (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    assignment_id      BIGINT   NOT NULL,
    course_version_id  BIGINT   NOT NULL COMMENT '由 section 反查并校验同树，冗余便于按课程版本聚合',
    section_id         BIGINT   NOT NULL,
    resource_id        BIGINT   NOT NULL COMMENT '必须是 VIDEO；NOT NULL 以避免 MySQL 唯一键对 NULL 不去重',
    effective_seconds  INT      NOT NULL DEFAULT 0 COMMENT '服务端累加的有效观看时长明细，单章节封顶 duration_seconds',
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_learning_assignment_section_resource (assignment_id, section_id, resource_id),
    KEY idx_learning_assignment_course (assignment_id, course_version_id),
    KEY idx_learning_section (section_id),
    CONSTRAINT fk_learning_assignment FOREIGN KEY (assignment_id) REFERENCES training_assignment (id),
    CONSTRAINT fk_learning_course_version FOREIGN KEY (course_version_id) REFERENCES course_version (id),
    CONSTRAINT fk_learning_section FOREIGN KEY (section_id) REFERENCES course_section (id),
    CONSTRAINT fk_learning_resource FOREIGN KEY (resource_id) REFERENCES course_resource (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '视频学习时长归属明细（无计时字段，时钟在 assignment 上）';

CREATE TABLE resource_open_log (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    assignment_id BIGINT   NOT NULL,
    resource_id   BIGINT   NOT NULL COMMENT '必须是非 VIDEO；视频走心跳，两条路径互斥',
    opened_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次打开时间，重复登记不覆盖',
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_assignment_resource (assignment_id, resource_id),
    KEY idx_open_resource (resource_id),
    CONSTRAINT fk_open_assignment FOREIGN KEY (assignment_id) REFERENCES training_assignment (id),
    CONSTRAINT fk_open_resource FOREIGN KEY (resource_id) REFERENCES course_resource (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '非视频资源首次打开登记（append-only，幂等）';