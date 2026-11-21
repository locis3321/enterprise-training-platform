package com.insurance.training.report.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 个人学习与考试明细。 */
public record LearnerReportVo(
        long employeeId,
        String employeeName,
        String phoneMasked,
        long enterpriseId,
        String enterpriseName,
        List<TaskDetail> tasks) {

    public record TaskDetail(
            long taskId,
            String taskTitle,
            String assignmentStatus,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            int totalSeconds,
            List<CourseDetail> courses,
            List<AttemptRow> attempts,
            Long bestAttemptId) {
    }

    public record CourseDetail(
            long courseVersionId,
            String courseTitle,
            int totalSeconds,
            List<SectionDetail> sections) {
    }

    public record SectionDetail(
            long sectionId,
            String title,
            boolean completed,
            int effectiveSeconds,
            int requiredSeconds) {
    }

    public record AttemptRow(
            long attemptId,
            String paperTitle,
            Integer scoreSum,
            Integer totalScore,
            Integer passScore,
            boolean passed,
            LocalDateTime startedAt,
            LocalDateTime submittedAt,
            boolean autoSubmitted,
            boolean best) {
    }
}
