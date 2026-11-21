package com.insurance.training.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.content.entity.Course;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {
}
