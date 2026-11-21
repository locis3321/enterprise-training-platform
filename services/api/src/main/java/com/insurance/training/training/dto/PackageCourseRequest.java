package com.insurance.training.training.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 关联课程版本到培训包草稿版本。{@code courseVersionId} 必须是已发布的课程版本。 */
public record PackageCourseRequest(
        @NotNull(message = "courseVersionId 不能为空")
        Long courseVersionId,

        @NotNull(message = "sortNo 不能为空")
        @Min(value = 1, message = "sortNo 必须从 1 开始")
        Integer sortNo
) {
}