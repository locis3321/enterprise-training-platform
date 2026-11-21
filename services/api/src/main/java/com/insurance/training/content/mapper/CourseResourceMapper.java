package com.insurance.training.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.content.entity.CourseResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CourseResourceMapper extends BaseMapper<CourseResource> {

    /**
     * 记录视频真实时长：**只在为空时写入**（首次写入者胜）。
     *
     * <p>为什么不允许覆盖：时长是文件属性，客户端上报值不可完全信任。
     * 若允许覆盖，学员改一个数字就能把「该看多久」改小，进而伪造完成。
     * 首次写入后锁定，后续不一致的上报只记日志、不改库。
     *
     * @return 1 表示本次写入；0 表示已有值（未改动）
     */
    @Update("""
            UPDATE course_resource
               SET duration_seconds = #{durationSeconds}
             WHERE id = #{resourceId}
               AND kind = 'VIDEO'
               AND duration_seconds IS NULL
               AND #{durationSeconds} > 0
            """)
    int fillVideoDurationIfAbsent(@Param("resourceId") long resourceId,
                                 @Param("durationSeconds") int durationSeconds);
}
