package com.insurance.training.training.vo;

import com.insurance.training.training.entity.TrainingPackageVersion;

import java.time.LocalDateTime;

/** 培训包版本概要（版本列表用），不含课程明细。 */
public record PackageVersionVo(
        long id,
        int versionNo,
        String title,
        String description,
        String status,
        LocalDateTime publishedAt,
        long courseCount
) {

    public static PackageVersionVo of(TrainingPackageVersion entity, long courseCount) {
        return new PackageVersionVo(entity.getId(), entity.getVersionNo(), entity.getTitle(),
                entity.getDescription(), entity.getStatus(), entity.getPublishedAt(), courseCount);
    }
}