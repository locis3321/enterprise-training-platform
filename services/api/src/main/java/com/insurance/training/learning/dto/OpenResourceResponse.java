package com.insurance.training.learning.dto;

/** 资源打开结果。{@code firstOpen=false} 表示此前已登记过（幂等）。 */
public record OpenResourceResponse(
        boolean firstOpen,
        String assignmentStatus,
        boolean completed
) {
}