package com.insurance.training.exam.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 保存单题答案（幂等覆盖）。 */
public record SaveAnswerRequest(
        @NotNull(message = "attemptId 不能为空") Long attemptId,
        @NotNull(message = "questionId 不能为空") Long questionId,
        /** 所选选项标签，如 ["A"] 或 ["A","C"]；空数组表示清空该题。 */
        List<String> labels
) {
}
