package com.insurance.training.exam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 题目新增/修改。
 *
 * <p>正确答案通过 {@link OptionRequest#correct()} 表达，服务端据此校验
 * 「单选恰好 1 个 / 多选至少 2 个」——不单独接收 correctLabels，避免两处不一致。
 */
public record QuestionRequest(
        @NotBlank(message = "题干不能为空")
        @Size(max = 2000, message = "题干长度不能超过 2000")
        String stem,

        @NotBlank(message = "题型不能为空")
        @Pattern(regexp = "SINGLE|MULTIPLE", message = "题型只能是 SINGLE 或 MULTIPLE")
        String type,

        @NotEmpty(message = "至少需要 2 个选项")
        @Size(min = 2, max = 5, message = "选项数量必须在 2-5 之间")
        @Valid
        List<OptionRequest> options
) {

    /** 单个选项；{@code correct=true} 表示该项是正确答案。 */
    public record OptionRequest(
            @NotBlank(message = "选项内容不能为空")
            @Size(max = 500, message = "选项内容不能超过 500")
            String content,

            boolean correct
    ) {
    }
}
