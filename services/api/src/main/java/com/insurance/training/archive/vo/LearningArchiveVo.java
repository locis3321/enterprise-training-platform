package com.insurance.training.archive.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学员学习档案：累计汇总 + 任务归档列表。
 *
 * <p>汇总由服务端一次算出，前端不再自行累加，避免与报表口径分叉。
 * 完成度直接读 {@code training_assignment.status}（服务端判定，只进不退）。
 */
public record LearningArchiveVo(Summary summary, List<TaskRow> tasks) {

    /**
     * @param taskTotal      被下发过的任务数
     * @param taskCompleted  已完成任务数
     * @param courseTotal    任务内课程数合计（同一课程在不同任务中分别计数）
     * @param courseCompleted 已完成课程数合计
     * @param studyHours     有效学时（服务端累计秒数 / 3600，1 位小数）
     * @param examPassed     已通过考试的任务数（按任务取最佳成绩）
     */
    public record Summary(
            int taskTotal,
            int taskCompleted,
            int courseTotal,
            int courseCompleted,
            double studyHours,
            int examPassed) {
    }

    public record TaskRow(
            long assignmentId,
            long taskId,
            String title,
            String enterpriseName,
            String status,
            LocalDateTime startAt,
            LocalDateTime learnDeadlineAt,
            LocalDateTime completedAt,
            int courseTotal,
            int courseCompleted,
            int sectionTotal,
            int sectionCompleted,
            double studyHours,
            /** 该任务最佳考试成绩；未考过为 null。 */
            ExamBrief bestExam) {
    }

    public record ExamBrief(
            Integer scoreSum,
            Integer totalScore,
            boolean passed,
            LocalDateTime submittedAt) {
    }
}
