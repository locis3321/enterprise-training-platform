package com.insurance.training.content.vo;

import java.util.List;

/** 课程详情（管理端）。可指定版本查看草稿内容；版本号不存在返回 404，不回落最新。 */
public record AdminCourseDetailVo(
        long id,
        String title,
        String description,
        String status,
        Long coverResourceId,
        int latestVersionNo,
        Integer publishedVersionNo,
        DetailVersion version,
        List<ResourceVo> unboundResources
) {

    /** 指定版本的完整内容。 */
    public record DetailVersion(
            long versionId,
            int versionNo,
            String title,
            String description,
            String status,
            List<DetailSection> sections
    ) {
    }

    public record DetailSection(
            long id,
            String title,
            int sortNo,
            Integer durationSeconds,
            List<SectionResource> resources
    ) {
    }

    public record SectionResource(
            long resourceId,
            String kind,
            String originalName,
            long sizeBytes,
            int sortNo
    ) {
    }
}
