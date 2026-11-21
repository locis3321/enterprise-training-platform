package com.insurance.training.learning.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 学员学习进度：任务级汇总 + 章节级明细。 */
public record MiniappProgressVo(
        long assignmentId,
        String title,
        String status,
        LocalDateTime learnDeadlineAt,
        long courseTotal,
        long courseDone,
        long sectionTotal,
        long sectionDone,
        boolean completed,
        List<SectionProgress> sections
) {

    /**
     * 章节进度。
     *
     * <p>只有「已完成 / 未完成」两种状态，不再有观看时长或百分比——
     * 视频禁用拖动后，「播完」即完成；资料章节「打开即完成」。
     * 课程进度 = 已完成章节数 / 总章节数。
     */
    public record SectionProgress(
            long sectionId,
            long courseVersionId,
            String title,
            Integer durationSeconds,
            boolean done
    ) {
    }
}
