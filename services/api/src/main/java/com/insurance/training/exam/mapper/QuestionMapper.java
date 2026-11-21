package com.insurance.training.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insurance.training.exam.entity.Question;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
