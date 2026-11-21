package com.insurance.training.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.content.entity.CourseSection;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 章节写操作。
 *
 * <p>三个写方法都把「所属版本仍为 DRAFT」放进同一条语句：先查状态再写存在竞争窗口，
 * 并发发布会改到已冻结的版本快照，而培训任务绑定的正是该快照。
 * 影响行数 0 即代表版本状态已变，由 Service 转 409。
 */
@Mapper
public interface CourseSectionMapper extends BaseMapper<CourseSection> {

    @Insert("""
            INSERT INTO course_section (course_version_id, title, sort_no, duration_seconds)
            SELECT #{courseVersionId}, #{title}, #{sortNo}, #{durationSeconds}
              FROM course_version
             WHERE id = #{courseVersionId} AND status = 'DRAFT'
            """)
    int insertIfDraft(@Param("courseVersionId") long courseVersionId,
                      @Param("title") String title,
                      @Param("sortNo") int sortNo,
                      @Param("durationSeconds") Integer durationSeconds);

    @Update("""
            UPDATE course_section s
              JOIN course_version v ON v.id = s.course_version_id
               SET s.title = #{title},
                   s.sort_no = #{sortNo},
                   s.duration_seconds = #{durationSeconds}
             WHERE s.id = #{id} AND s.course_version_id = #{courseVersionId} AND v.status = 'DRAFT'
            """)
    int updateIfDraft(@Param("id") long id,
                      @Param("courseVersionId") long courseVersionId,
                      @Param("title") String title,
                      @Param("sortNo") int sortNo,
                      @Param("durationSeconds") Integer durationSeconds);

    @Delete("""
            DELETE s FROM course_section s
              JOIN course_version v ON v.id = s.course_version_id
             WHERE s.id = #{id} AND s.course_version_id = #{courseVersionId} AND v.status = 'DRAFT'
            """)
    int deleteIfDraft(@Param("id") long id, @Param("courseVersionId") long courseVersionId);
}