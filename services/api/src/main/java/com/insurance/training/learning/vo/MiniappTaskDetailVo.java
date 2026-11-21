package com.insurance.training.learning.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 学员任务详情：包内课程/章节/资源 + 我的累计时长。 */
public record MiniappTaskDetailVo(
        long assignmentId,
        String title,
        LocalDateTime startAt,
        LocalDateTime learnDeadlineAt,
        String status,
        long courseTotal,
        long courseDone,
        long sectionTotal,
        long sectionDone,
        List<CourseItem> courses
) {

    public record CourseItem(
            long courseVersionId,
            Long courseId,
            String title,
            Long coverResourceId,
            List<SectionItem> sections
    ) {
    }

    public record SectionItem(
            long sectionId,
            String title,
            Integer sortNo,
            Integer durationSeconds,
            boolean done,
            List<ResourceItem> resources
    ) {
    }

    public record ResourceItem(
            long resourceId,
            String kind,
            String originalName,
            long sizeBytes
    ) {
    }
}