package com.insurance.training.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.learning.entity.LearningRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 视频学习记录。
 *
 * <p>完成判定只看 {@code completed}：视频播完（ended）置 1，重复上报幂等。
 * 不再累加时长——播放器已禁用拖动，「播完」本身就是可信的完成信号。
 */
@Mapper
public interface LearningRecordMapper extends BaseMapper<LearningRecord> {

    /**
     * 标记该视频已播完（幂等，只写不回退）。
     *
     * <p>单语句 upsert：并发重复上报同一视频时不会报错，也不会把 completed 改回 0。
     *
     * @return 影响行数（MySQL 语义下 1=新插入或实际更新，0=已存在且值未变）
     */
    @Insert("""
            INSERT INTO learning_record (assignment_id, course_version_id, section_id, resource_id,
                                         effective_seconds, completed)
            VALUES (#{assignmentId}, #{courseVersionId}, #{sectionId}, #{resourceId}, 0, 1)
            ON DUPLICATE KEY UPDATE completed = 1
            """)
    int markCompleted(@Param("assignmentId") long assignmentId,
                      @Param("courseVersionId") long courseVersionId,
                      @Param("sectionId") long sectionId,
                      @Param("resourceId") long resourceId);

    /**
     * 累计该视频的有效观看时长（按视频时长封顶）。
     *
     * <p>**不用于完成判定**（完成只看 ended + 服务端校验），只用于：
     * <ul>
     *   <li>校验客户端声称的 finished 是否可信（累计时长需接近视频时长）；</li>
     *   <li>管理端查看学习投入。</li>
     * </ul>
     */
    @Insert("""
            INSERT INTO learning_record (assignment_id, course_version_id, section_id, resource_id,
                                         effective_seconds, completed)
            VALUES (#{assignmentId}, #{courseVersionId}, #{sectionId}, #{resourceId}, LEAST(#{credit}, #{cap}), 0)
            ON DUPLICATE KEY UPDATE
                effective_seconds = LEAST(effective_seconds + #{credit}, #{cap})
            """)
    int accumulate(@Param("assignmentId") long assignmentId,
                   @Param("courseVersionId") long courseVersionId,
                   @Param("sectionId") long sectionId,
                   @Param("resourceId") long resourceId,
                   @Param("credit") int credit,
                   @Param("cap") int cap);
}
