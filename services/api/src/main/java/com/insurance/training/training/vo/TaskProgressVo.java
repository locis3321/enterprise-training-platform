package com.insurance.training.training.vo;

/** 任务进度统计。{@code total == completed + inProgress + notStarted}。 */
public record TaskProgressVo(
        long total,
        long completed,
        long inProgress,
        long notStarted
) {
}