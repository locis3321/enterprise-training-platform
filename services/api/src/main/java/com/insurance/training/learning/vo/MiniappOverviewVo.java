package com.insurance.training.learning.vo;

/** 学员总览统计：首页三个数字的权威来源。 */
public record MiniappOverviewVo(
        long pendingCourses,
        long completedCourses,
        long pendingExams,
        boolean examsAvailable
) {
}
