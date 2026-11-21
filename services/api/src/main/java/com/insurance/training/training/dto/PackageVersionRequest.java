package com.insurance.training.training.dto;

import jakarta.validation.constraints.Size;

/** 新建/修改培训包版本时可覆盖标题与简介（缺省沿用包标题）。 */
public record PackageVersionRequest(
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "简介长度不能超过 2000")
        String description
) {
}