package com.insurance.training.content.vo;

import com.insurance.training.content.entity.Course;

import java.time.LocalDateTime;

/** 课程列表/详情（不含版本内容）。 */
public record CourseVo(
        long id,
        long insuranceCompanyId,
        Long ownerEnterpriseId,
        String title,
        String description,
        Long coverResourceId,
        String status,
        int latestVersionNo,
        Integer publishedVersionNo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CourseVo of(Course entity) {
        return new CourseVo(entity.getId(), entity.getInsuranceCompanyId(), entity.getOwnerEnterpriseId(),
                entity.getTitle(), entity.getDescription(), entity.getCoverResourceId(),
                entity.getStatus(), entity.getLatestVersionNo() == null ? 0 : entity.getLatestVersionNo(),
                entity.getPublishedVersionNo(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
