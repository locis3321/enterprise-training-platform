package com.insurance.training.training.vo;

/**
 * 包内一门课程版本。
 *
 * <p>{@code courseVersionTitle} 是发布时的快照标题（不可变），
 * {@code courseTitle} 是课程当前标题（列表权威来源，可能被改名）；两者刻意分开，避免快照被展示层改写。
 * {@code courseStatus} 供管理端提示「课程已下线」，建任务时由服务端拒绝。
 */
public record PackageCourseVo(
        long courseVersionId,
        long courseId,
        String courseTitle,
        int courseVersionNo,
        String courseVersionTitle,
        String courseStatus,
        int sortNo
) {
}