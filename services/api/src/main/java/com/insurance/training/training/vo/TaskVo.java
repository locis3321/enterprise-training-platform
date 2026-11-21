package com.insurance.training.training.vo;

import com.insurance.training.training.entity.TrainingTask;

import java.time.LocalDateTime;

/** 培训任务（列表与详情共用）。 */
public record TaskVo(
        long id,
        long insuranceCompanyId,
        long enterpriseId,
        long packageVersionId,
        String title,
        LocalDateTime startAt,
        LocalDateTime learnDeadlineAt,
        String status,
        LocalDateTime createdAt
) {

    public static TaskVo of(TrainingTask entity) {
        return new TaskVo(entity.getId(), entity.getInsuranceCompanyId(), entity.getEnterpriseId(),
                entity.getPackageVersionId(), entity.getTitle(), entity.getStartAt(),
                entity.getLearnDeadlineAt(), entity.getStatus(), entity.getCreatedAt());
    }
}