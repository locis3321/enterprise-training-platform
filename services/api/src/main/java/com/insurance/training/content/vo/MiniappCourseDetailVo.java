package com.insurance.training.content.vo;

import java.util.List;

/** 学员视角的课程详情（只含已发布版本）。 */
public record MiniappCourseDetailVo(
        long id,
        String title,
        String description,
        int versionNo,
        int sectionCount,
        List<Section> sections
) {

    public record Section(
            long id,
            String title,
            int sortNo,
            Integer durationSeconds,
            int resourceCount,
            List<ResourceBrief> resources
    ) {
    }

    /**
     * 资源简要信息。不包含 URL——URL 必须由学员单独申请，以便每次校验权限。
     */
    public record ResourceBrief(
            long resourceId,
            String kind,
            String originalName,
            long sizeBytes
    ) {
    }
}
