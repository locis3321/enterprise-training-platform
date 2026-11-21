package com.insurance.training.content.vo;

import com.insurance.training.content.entity.CourseVersion;

import java.time.LocalDateTime;

/** 版本概要（版本列表用），不含章节明细。 */
public record CourseVersionVo(
        long id,
        int versionNo,
        String title,
        String status,
        LocalDateTime publishedAt,
        long sectionCount,
        long resourceCount
) {

    public static CourseVersionVo of(CourseVersion entity, long sectionCount, long resourceCount) {
        return new CourseVersionVo(entity.getId(), entity.getVersionNo(), entity.getTitle(),
                entity.getStatus(), entity.getPublishedAt(), sectionCount, resourceCount);
    }
}
