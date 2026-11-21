-- ============================================================================
-- V3：课程、章节与资源（Task 03）
--
-- 数据模型要点（见 docs/memory/tasks-2611-0915-47/T1-迁移V3课程表.md）：
--   1. 五表：course / course_version / course_section / course_resource
--      / course_version_resource
--   2. 资源元数据与「版本—章节—资源」绑定分离：
--      - course_resource.object_key 唯一（一个对象只有一份元数据）
--      - course_version_resource 是引用关系，同一资源可被多个版本绑定
--      - 版本复制（copy）只复制章节与绑定行，不复制元数据与对象
--   3. 发布后版本不可变：仅 DRAFT 允许改标题/章节/绑定
--   4. 状态约定差异说明：V2 用 status TINYINT(1/0) 表达启停；
--      内容域需要多态生命周期（DRAFT/PUBLISHED/OFFLINE），故用 VARCHAR 枚举。
--   5. 资源删除只有一个事实源 course_resource.status（ACTIVE/DELETED），
--      不设 deleted 列，避免两套过滤条件不一致。
--   6. 时间统一 DATETIME，存 Asia/Shanghai 本地时间（D-04）。
-- ============================================================================

CREATE TABLE course (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    insurance_company_id  BIGINT       NOT NULL COMMENT '可见范围边界：同公司下所有企业可见',
    owner_enterprise_id   BIGINT                DEFAULT NULL COMMENT '写权限归属企业；NULL=保险公司级课程（仅超管/公司管理员可建）',
    title                 VARCHAR(200) NOT NULL COMMENT '课程名，列表与检索的权威来源',
    description           VARCHAR(2000)         DEFAULT NULL,
    cover_resource_id     BIGINT                DEFAULT NULL COMMENT '课程级封面，不参与版本快照',
    status                VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / OFFLINE',
    latest_version_no     INT          NOT NULL DEFAULT 0 COMMENT '最新版本号（含草稿）',
    published_version_no  INT                   DEFAULT NULL COMMENT '最近一次发布且当前生效的版本号；NULL=从未发布',
    created_by_admin_id   BIGINT                DEFAULT NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 / 1 已删',
    PRIMARY KEY (id),
    KEY idx_course_company_status (insurance_company_id, status, deleted),
    KEY idx_course_owner_enterprise (owner_enterprise_id),
    CONSTRAINT fk_course_company FOREIGN KEY (insurance_company_id) REFERENCES insurance_company (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程（版本容器）';

CREATE TABLE course_version (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    course_id              BIGINT        NOT NULL,
    version_no             INT           NOT NULL,
    title                  VARCHAR(200)  NOT NULL COMMENT '版本快照标题，发布后不可变',
    description            VARCHAR(2000)          DEFAULT NULL,
    status                 VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    published_at           DATETIME               DEFAULT NULL,
    published_by_admin_id  BIGINT                 DEFAULT NULL,
    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_course_no (course_id, version_no),
    KEY idx_version_course_status (course_id, status),
    CONSTRAINT fk_version_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程版本（发布快照）';

CREATE TABLE course_section (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    course_version_id  BIGINT       NOT NULL,
    title              VARCHAR(200) NOT NULL,
    sort_no            INT          NOT NULL COMMENT '同版本内唯一，从 1 开始',
    duration_seconds   INT                   DEFAULT NULL COMMENT '视频章节可填，用于展示与学习时长校验',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_section_version_sort (course_version_id, sort_no),
    CONSTRAINT fk_section_version FOREIGN KEY (course_version_id) REFERENCES course_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程章节';

CREATE TABLE course_resource (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    course_id             BIGINT       NOT NULL COMMENT '上传时归属课程；未绑定时仍可在此课程下检索',
    insurance_company_id  BIGINT       NOT NULL COMMENT '冗余，鉴权与按公司统计',
    kind                  VARCHAR(16)  NOT NULL COMMENT 'VIDEO / PDF / PPT / IMAGE',
    object_key            VARCHAR(512) NOT NULL COMMENT 'MinIO 对象键，存储位置唯一事实源',
    original_name         VARCHAR(255) NOT NULL COMMENT '仅用于展示，不参与路径拼接',
    content_type          VARCHAR(128) NOT NULL COMMENT '服务端按字节签名判定的真实类型',
    size_bytes            BIGINT       NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DELETED（唯一删除事实源）',
    created_by_admin_id   BIGINT                DEFAULT NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_object_key (object_key),
    KEY idx_resource_course_status (course_id, status),
    KEY idx_resource_company (insurance_company_id),
    CONSTRAINT fk_resource_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '课程资源元数据（与版本解耦）';

CREATE TABLE course_version_resource (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    course_version_id  BIGINT   NOT NULL,
    section_id         BIGINT   NOT NULL,
    resource_id        BIGINT   NOT NULL,
    sort_no            INT      NOT NULL DEFAULT 1 COMMENT '章节内排序',
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_binding_version_section_resource (course_version_id, section_id, resource_id),
    KEY idx_binding_section (section_id, sort_no),
    KEY idx_binding_resource (resource_id),
    CONSTRAINT fk_binding_version FOREIGN KEY (course_version_id) REFERENCES course_version (id),
    CONSTRAINT fk_binding_section FOREIGN KEY (section_id) REFERENCES course_section (id),
    CONSTRAINT fk_binding_resource FOREIGN KEY (resource_id) REFERENCES course_resource (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '版本-章节-资源绑定（引用关系，可跨版本复用同一资源）';
