package com.insurance.training.archive.vo;

import java.time.LocalDateTime;

/**
 * 考试记录流水的一行：跨任务、按交卷时间倒序。
 *
 * <p>与「考试」页（只列待考）和「成绩」页（单任务最佳）不同，这里是**全部历史**，
 * 每次交卷一行，不合并、不覆盖。
 */
public record ExamRecordVo(
        long attemptId,
        long assignmentId,
        String taskTitle,
        String enterpriseName,
        String paperTitle,
        Integer scoreSum,
        Integer totalScore,
        Integer passScore,
        boolean passed,
        boolean autoSubmitted,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        /** 是否为该任务的最佳成绩（与成绩页同一口径）。 */
        boolean best) {
}
