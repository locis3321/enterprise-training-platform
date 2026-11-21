package com.insurance.training.common.response;

import java.util.List;

/**
 * 统一分页结果。
 *
 * <p>字段命名对齐前端习惯：{@code items / total / page / pageSize}。
 * {@code page} 从 1 开始；{@code pageSize} 上限由服务端校验（见 application.yml 的 app.page.max-size）。
 */
public record PageResult<T>(List<T> items, long total, long page, long pageSize) {

    public static <T> PageResult<T> of(List<T> items, long total, long page, long pageSize) {
        return new PageResult<>(items == null ? List.of() : items, total, page, pageSize);
    }

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize);
    }
}
