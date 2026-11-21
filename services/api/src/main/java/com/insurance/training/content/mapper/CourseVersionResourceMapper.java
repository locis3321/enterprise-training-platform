package com.insurance.training.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.content.entity.CourseVersionResource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 版本—章节—资源绑定写操作。
 *
 * <p>写方法把「版本仍为 DRAFT」与写入放进同一语句，避免并发发布后绑定被写进已冻结快照。
 * 影响行数 0 → 由 Service 转 409。
 */
@Mapper
public interface CourseVersionResourceMapper extends BaseMapper<CourseVersionResource> {

    @Insert("""
            INSERT INTO course_version_resource (course_version_id, section_id, resource_id, sort_no)
            SELECT #{courseVersionId}, #{sectionId}, #{resourceId}, #{sortNo}
              FROM course_version
             WHERE id = #{courseVersionId} AND status = 'DRAFT'
            """)
    int insertIfDraft(@Param("courseVersionId") long courseVersionId,
                      @Param("sectionId") long sectionId,
                      @Param("resourceId") long resourceId,
                      @Param("sortNo") int sortNo);

    @Delete("""
            DELETE b FROM course_version_resource b
              JOIN course_version v ON v.id = b.course_version_id
             WHERE b.course_version_id = #{courseVersionId}
               AND b.section_id = #{sectionId}
               AND b.resource_id = #{resourceId}
               AND v.status = 'DRAFT'
            """)
    int deleteIfDraft(@Param("courseVersionId") long courseVersionId,
                      @Param("sectionId") long sectionId,
                      @Param("resourceId") long resourceId);
}