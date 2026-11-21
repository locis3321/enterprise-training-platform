package com.insurance.training.report.vo;

import java.time.LocalDateTime;

/** 任务维度汇总。 */
public record TaskSummaryVo(
        long taskId,
        String taskTitle,
        long enterpriseId,
        String enterpriseName,
        long packageVersionId,
        LocalDateTime startAt,
        LocalDateTime learnDeadlineAt,
        String taskStatus,
        int assignedCount,
        int learningCompleted,
        double completionRate,
        int examTakers,
        int examPassed,
        double examPassRate,
        int overdueCount) {
}
