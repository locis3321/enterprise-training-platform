package com.insurance.training.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.training.entity.TrainingAssignment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 任务分配。
 *
 * <p>{@code insertIfTaskOpen} 把「任务仍为 PUBLISHED」与插入放进同一语句：
 * 先查任务状态再插入存在窗口，关闭后仍会被追加分配。
 * 影响行数 0 → 任务已关闭或已删除，由 Service 转 409。
 */
@Mapper
public interface TrainingAssignmentMapper extends BaseMapper<TrainingAssignment> {

    @Insert("""
            INSERT INTO training_assignment (task_id, employee_id, package_version_id, status)
            SELECT #{taskId}, #{employeeId}, #{packageVersionId}, 'NOT_STARTED'
              FROM training_task
             WHERE id = #{taskId} AND status = 'PUBLISHED'
            """)
    int insertIfTaskOpen(TrainingAssignment assignment);

    /**
     * 推进任务级计时时钟：条件更新是并发闸门。
     *
     * <p>用「时钟值仍等于读到的 observed」作为条件；影响 0 行说明另一个并发心跳
     * 已经推进过时钟，本次必须计 0，否则同一段真实时间会被计入两次。
     */
    @Update("""
            <script>
            UPDATE training_assignment
               SET last_heartbeat_at = #{now}
             WHERE id = #{assignmentId}
               <choose>
                 <when test="observed == null">AND last_heartbeat_at IS NULL</when>
                 <otherwise>AND last_heartbeat_at = #{observed}</otherwise>
               </choose>
            </script>
            """)
    int advanceClockIfUnchanged(@Param("assignmentId") long assignmentId,
                                @Param("observed") LocalDateTime observed,
                                @Param("now") LocalDateTime now);

    /** NOT_STARTED → IN_PROGRESS，并记录首次开始时间；已开始的不会被覆盖。 */
    @Update("""
            UPDATE training_assignment
               SET status = 'IN_PROGRESS',
                   started_at = COALESCE(started_at, #{now})
             WHERE id = #{assignmentId} AND status = 'NOT_STARTED'
            """)
    int markInProgress(@Param("assignmentId") long assignmentId, @Param("now") LocalDateTime now);

    /** 完成状态只写不回退：仅在尚未完成时写入。 */
    @Update("""
            UPDATE training_assignment
               SET status = 'COMPLETED', completed_at = #{now}
             WHERE id = #{assignmentId} AND status != 'COMPLETED'
            """)
    int completeIfNotCompleted(@Param("assignmentId") long assignmentId, @Param("now") LocalDateTime now);
}