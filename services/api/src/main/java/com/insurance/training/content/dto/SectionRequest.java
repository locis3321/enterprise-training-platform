package com.insurance.training.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 章节新增/修改。 */
public record SectionRequest(
        @NotBlank(message = "章节标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Min(value = 1, message = "排序号必须从 1 开始")
        Integer sortNo,

        @Min(value = 0, message = "时长不能为负")
        Integer durationSeconds
) {
}
