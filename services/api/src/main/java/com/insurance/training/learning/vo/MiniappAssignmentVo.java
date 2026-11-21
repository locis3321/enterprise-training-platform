package com.insurance.training.learning.vo;

import com.insurance.training.learning.service.LearningProgressService;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;

import java.time.LocalDateTime;

/** 学员「我的任务」列表项。{@code daysLeft} 为负数表示已过期。 */
public record MiniappAssignmentVo(
        long assignmentId,
        long taskId,
        String title,
        String status,
        LocalDateTime startAt,
        LocalDateTime learnDeadlineAt,
        Long daysLeft,
        long courseTotal,
        long courseDone,
        long sectionTotal,
        long sectionDone
) {

    public static MiniappAssignmentVo of(TrainingAssignment assignment, TrainingTask task,
                                         LearningProgressService.Progress progress, LocalDateTime now) {
        Long daysLeft = null;
        if (task != null && task.getLearnDeadlineAt() != null) {
            daysLeft = java.time.Duration.between(now, task.getLearnDeadlineAt()).toDays();
        }
        return new MiniappAssignmentVo(assignment.getId(),
                assignment.getTaskId(),
                task == null ? null : task.getTitle(),
                progress.completed() ? "COMPLETED" : assignment.getStatus(),
                task == null ? null : task.getStartAt(),
                task == null ? null : task.getLearnDeadlineAt(),
                daysLeft,
                progress.courseTotal(), progress.courseDone(),
                progress.sectionTotal(), progress.sectionDone());
    }
}