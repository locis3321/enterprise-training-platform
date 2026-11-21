package com.insurance.training.exam.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 组卷请求：从包内各课程抽题，生成试卷。 */
public record PaperCreateRequest(
        @NotNull(message = "培训包不能为空")
        Long packageId,

        @NotBlank(message = "试卷名称不能为空")
        @Size(max = 200, message = "试卷名称不能超过 200")
        String title,

        /** 每门课的抽题数量。课程必须属于该培训包。 */
        @NotEmpty(message = "请至少为一门课程指定抽题数量")
        List<DrawRule> draws,

        @Min(value = 1, message = "考试时长至少 1 分钟")
        @Max(value = 600, message = "考试时长不能超过 600 分钟")
        Integer durationMinutes,

        @Min(value = 1, message = "及格百分比至少为 1")
        @Max(value = 100, message = "及格百分比不能超过 100")
        Integer passScore
) {

    /** 抽题规则：某课程抽几题（单选多选混合随机）。 */
    public record DrawRule(
            @NotNull(message = "课程不能为空") Long courseId,
            @Min(value = 1, message = "抽题数量至少 1") Integer count
    ) {
    }
}
