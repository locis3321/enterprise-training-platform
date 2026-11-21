package com.insurance.training.content.vo;

import com.insurance.training.content.entity.CourseResource;

/** 资源元数据视图（不含 URL；URL 必须每次按需签名）。 */
public record ResourceVo(
        long id,
        long courseId,
        String kind,
        String originalName,
        String contentType,
        long sizeBytes,
        String status
) {

    public static ResourceVo of(CourseResource entity) {
        return new ResourceVo(entity.getId(), entity.getCourseId(), entity.getKind(),
                entity.getOriginalName(), entity.getContentType(),
                entity.getSizeBytes() == null ? 0 : entity.getSizeBytes(), entity.getStatus());
    }
}
