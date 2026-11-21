package com.insurance.training.training.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 追加分配员工。任务关闭后返回 409。 */
public record AssignRequest(
        @NotEmpty(message = "请至少选择一个员工")
        List<Long> employeeIds
) {
}