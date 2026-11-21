-- ============================================================================
-- V7：视频以「播完」为完成信号，取消时长计算
--
-- 背景（2026-11-10 决策，用户要求）：
--   视频已禁止拖动（controls=false / enable-progress-gesture=false），
--   播放到结尾会触发 ended 事件。因此「看完」是客户端能给出的、且无法绕过的信号：
--   想播完就必须真的从头播到尾，拖动被禁用。
--
-- 修订：不再计算「看了多少秒」，改为「该视频是否已播完」的二元状态。
--   1. learning_record 增加 completed 标记；effective_seconds 保留但不再参与任何判定
--      （历史数据仍可查，避免不可逆删除）。
--   2. effective_seconds 改为可空语义上的「历史字段」：新写入恒为 0。
--   3. 章节完成 = 该章节全部 VIDEO 资源均已 completed；
--      纯资料章节完成 = 全部资源已 open（沿用 resource_open_log）。
--   4. 课程完成 = 全部章节完成；任务完成 = 全部课程完成。
--      进度百分比 = 已完成章节数 / 总章节数（不再按时长折算）。
--
-- 为什么这样可以去掉时长：完成判定只需要「章节内每个视频都播完」这个事实，
--   不需要知道视频有多长。心跳仍保留，用于证明「正在播放中」（防止不播只报完成）。
-- ============================================================================

ALTER TABLE learning_record
    ADD COLUMN completed TINYINT NOT NULL DEFAULT 0
        COMMENT '1=该视频已播放完成（ended 事件上报）；0=未完成';

-- 历史数据：已看时长达到过时长的视为已完成（仅此一次迁移用旧口径换算，
-- 之后 completed 只由 ended 事件驱动）。effective_seconds 为 0 的一律不算完成。
UPDATE learning_record r
    JOIN course_section s ON s.id = r.section_id
   SET r.completed = 1
 WHERE s.duration_seconds IS NOT NULL
   AND s.duration_seconds > 0
   AND r.effective_seconds >= s.duration_seconds;
