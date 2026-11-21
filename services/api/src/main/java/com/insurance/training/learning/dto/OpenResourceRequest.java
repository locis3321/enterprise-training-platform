package com.insurance.training.learning.dto;

import jakarta.validation.constraints.NotNull;

/** 非视频资源「已打开」登记。 */
public record OpenResourceRequest(
        @NotNull(message = "assignmentId 不能为空")
        Long assignmentId,

        @NotNull(message = "resourceId 不能为空")
        Long resourceId
) {
}