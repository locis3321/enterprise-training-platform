package com.insurance.training.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.training.entity.PackageCourse;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 培训包—课程关联。
 *
 * <p>三个写方法都把「包版本仍为 DRAFT」放进同一条语句：先查状态再写存在竞争窗口，
 * 并发发布会把课程追加进已冻结的快照。影响行数 0 即代表状态已变，由 Service 转 409。
 */
@Mapper
public interface PackageCourseMapper extends BaseMapper<PackageCourse> {

    @Insert("""
            INSERT INTO package_course (package_version_id, course_version_id, sort_no)
            SELECT #{packageVersionId}, #{courseVersionId}, #{sortNo}
              FROM training_package_version
             WHERE id = #{packageVersionId} AND status = 'DRAFT'
            """)
    int insertIfDraft(@Param("packageVersionId") long packageVersionId,
                      @Param("courseVersionId") long courseVersionId,
                      @Param("sortNo") int sortNo);

    @Update("""
            UPDATE package_course pc
              JOIN training_package_version v ON v.id = pc.package_version_id
               SET pc.sort_no = #{sortNo}
             WHERE pc.id = #{id} AND v.status = 'DRAFT'
            """)
    int updateSortNoIfDraft(@Param("id") long id, @Param("sortNo") int sortNo);

    @Delete("""
            DELETE pc FROM package_course pc
              JOIN training_package_version v ON v.id = pc.package_version_id
             WHERE pc.package_version_id = #{packageVersionId}
               AND pc.course_version_id = #{courseVersionId}
               AND v.status = 'DRAFT'
            """)
    int deleteIfDraft(@Param("packageVersionId") long packageVersionId,
                      @Param("courseVersionId") long courseVersionId);
}