package com.insurance.training.training.vo;

import com.insurance.training.training.entity.TrainingPackage;

import java.time.LocalDateTime;

/** 培训包列表项（不含版本内容）。 */
public record PackageVo(
        long id,
        long insuranceCompanyId,
        Long ownerEnterpriseId,
        String title,
        String description,
        String status,
        int latestVersionNo,
        Integer publishedVersionNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static PackageVo of(TrainingPackage entity) {
        return new PackageVo(entity.getId(), entity.getInsuranceCompanyId(), entity.getOwnerEnterpriseId(),
                entity.getTitle(), entity.getDescription(), entity.getStatus(),
                entity.getLatestVersionNo() == null ? 0 : entity.getLatestVersionNo(),
                entity.getPublishedVersionNo(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}