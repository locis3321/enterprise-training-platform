-- ============================================================================
-- V9：题库、试卷与考试
--
-- 设计见 docs/memory/tasks-2611-1023-55/
--   1. 题库挂 course_id（不挂版本）：课程改版不影响已有题目，抽题与版本解耦。
--   2. 试卷挂 package_id（不挂包版本）：题目本身与版本无关。
--   3. 考试按 attempt 存题目快照：开考后管理员改题/停用不影响进行中的考试，
--      历史成绩的判分口径可解释。
--   4. 「同一学员同一试卷最多一个进行中 attempt」用生成列 + 唯一索引实现
--      （MySQL 唯一键对 NULL 不去重，非进行中的行生成列为 NULL 可无限多条）。
--   5. 及格分存百分比整数，判定用整数乘法避免浮点误差。
-- ============================================================================

CREATE TABLE question (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    course_id      BIGINT       NOT NULL COMMENT '题库按课程归属，与课程版本无关',
    type           VARCHAR(16)  NOT NULL COMMENT 'SINGLE（单选）/ MULTIPLE（多选）',
    stem           VARCHAR(2000) NOT NULL COMMENT '题干',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE（停用仅从抽题范围排除）',
    created_by_admin_id BIGINT           DEFAULT NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_question_course_status (course_id, status, deleted),
    CONSTRAINT fk_question_course FOREIGN KEY (course_id) REFERENCES course (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '题库：题目按课程归属';

CREATE TABLE question_option (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    question_id BIGINT       NOT NULL,
    label       VARCHAR(4)   NOT NULL COMMENT 'A-E',
    content     VARCHAR(500) NOT NULL,
    is_correct  TINYINT      NOT NULL DEFAULT 0,
    sort_no     INT          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_option_question_label (question_id, label),
    KEY idx_option_question (question_id),
    CONSTRAINT fk_option_question FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '题目选项，2-5 个，正确答案由 is_correct 标记';

CREATE TABLE exam_paper (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    insurance_company_id BIGINT   NOT NULL COMMENT '可见范围边界，与培训包一致',
    package_id       BIGINT       NOT NULL COMMENT '试卷挂在培训包上（不挂版本）',
    title            VARCHAR(200) NOT NULL,
    duration_minutes INT          NOT NULL DEFAULT 60 COMMENT '考试时长（分钟）',
    pass_score       INT          NOT NULL DEFAULT 90 COMMENT '及格百分比 1-100',
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED',
    created_by_admin_id BIGINT           DEFAULT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_package (package_id),
    KEY idx_paper_company_status (insurance_company_id, status, deleted),
    CONSTRAINT fk_paper_package FOREIGN KEY (package_id) REFERENCES training_package (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '试卷：一个培训包一套，题目与分值可调，发布后锁定';

CREATE TABLE exam_paper_question (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    paper_id    BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    score       INT    NOT NULL DEFAULT 1 COMMENT '该题分值',
    sort_no     INT    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_question (paper_id, question_id),
    KEY idx_paper_question_paper (paper_id),
    KEY idx_paper_question_question (question_id),
    CONSTRAINT fk_pq_paper FOREIGN KEY (paper_id) REFERENCES exam_paper (id),
    CONSTRAINT fk_pq_question FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '试卷题目与分值；引用关系决定题目能否被编辑';

CREATE TABLE exam_attempt (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    assignment_id    BIGINT       NOT NULL,
    paper_id         BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS / SUBMITTED',
    started_at       DATETIME     NOT NULL,
    deadline_at      DATETIME     NOT NULL COMMENT '开考时按试卷时长计算，服务端为准',
    submitted_at     DATETIME              DEFAULT NULL,
    score_sum        INT          NOT NULL DEFAULT 0,
    total_score      INT          NOT NULL DEFAULT 0 COMMENT '开考时快照的总分',
    pass_score       INT          NOT NULL DEFAULT 90 COMMENT '开考时快照的及格百分比',
    passed           TINYINT      NOT NULL DEFAULT 0,
    auto_submitted   TINYINT      NOT NULL DEFAULT 0 COMMENT '1=切页/超时自动交卷',
    in_progress_key  BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'IN_PROGRESS' THEN assignment_id ELSE NULL END) STORED,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 「同一学员同一试卷最多一个进行中 attempt」；非进行中的行为 NULL 可多条
    UNIQUE KEY uk_attempt_one_in_progress (in_progress_key, paper_id),
    KEY idx_attempt_assignment (assignment_id),
    KEY idx_attempt_paper (paper_id),
    CONSTRAINT fk_attempt_assignment FOREIGN KEY (assignment_id) REFERENCES training_assignment (id),
    CONSTRAINT fk_attempt_paper FOREIGN KEY (paper_id) REFERENCES exam_paper (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '考试尝试：支持重复考试，历史全保留';

CREATE TABLE exam_attempt_question (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id   BIGINT       NOT NULL,
    question_id  BIGINT       NOT NULL,
    stem         VARCHAR(2000) NOT NULL COMMENT '开考时快照',
    type         VARCHAR(16)  NOT NULL,
    options_json TEXT         NOT NULL COMMENT '[{"label":"A","content":"..."}]',
    correct_json VARCHAR(255) NOT NULL COMMENT '正确答案标签，如 ["A","C"]；仅服务端判分使用',
    score        INT          NOT NULL,
    sort_no      INT          NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attempt_question (attempt_id, question_id),
    KEY idx_attempt_question_attempt (attempt_id),
    CONSTRAINT fk_aq_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempt (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '开考快照：管理员改题/停用不影响进行中的考试';

CREATE TABLE exam_attempt_answer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    attempt_id  BIGINT       NOT NULL,
    question_id BIGINT       NOT NULL,
    answer_json VARCHAR(255) NOT NULL COMMENT '学员所选标签，如 ["A"]',
    is_correct  TINYINT      NOT NULL DEFAULT 0,
    score       INT          NOT NULL DEFAULT 0,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_answer_attempt_question (attempt_id, question_id),
    KEY idx_answer_attempt (attempt_id),
    CONSTRAINT fk_answer_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempt (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学员答案，每题一行，重复保存覆盖';
