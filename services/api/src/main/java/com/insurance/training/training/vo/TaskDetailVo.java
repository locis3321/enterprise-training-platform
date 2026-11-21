package com.insurance.training.training.vo;

/** 任务详情：任务字段 + 进度统计。 */
public record TaskDetailVo(
        TaskVo task,
        TaskProgressVo progress
) {
}