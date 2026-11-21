package com.insurance.training.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建/复制版本时可覆盖标题与简介（缺省沿用课程标题）。 */
public record CourseVersionRequest(
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "简介长度不能超过 2000")
        String description
) {
}
