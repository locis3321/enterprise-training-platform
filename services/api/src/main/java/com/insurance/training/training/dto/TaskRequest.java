package com.insurance.training.training.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新建培训任务。
 *
 * <p>分配方式二选一：{@code assignAll=true} 取企业下当前所有启用员工的一次性快照；
 * 否则必须提供非空 {@code employeeIds}。两者都为空 → 400，不建出零分配的任务。
 */
public record TaskRequest(
        @NotNull(message = "enterpriseId 不能为空")
        Long enterpriseId,

        @NotNull(message = "packageVersionId 不能为空")
        Long packageVersionId,

        @NotBlank(message = "任务标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @NotNull(message = "startAt 不能为空")
        LocalDateTime startAt,

        @NotNull(message = "learnDeadlineAt 不能为空")
        LocalDateTime learnDeadlineAt,

        Boolean assignAll,

        List<Long> employeeIds
) {
}