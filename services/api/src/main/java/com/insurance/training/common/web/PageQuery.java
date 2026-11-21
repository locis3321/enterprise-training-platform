package com.insurance.training.common.web;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;

/**
 * 分页参数归一化。
 *
 * <p>页码从 1 开始；pageSize 缺省 10，超过上限直接 400（显式失败，不静默截断，
 * 避免前端以为拿到了全量数据）。上限来自 {@code app.page.max-size}。
 */
public record PageQuery(long page, long pageSize) {

    public static PageQuery of(Long page, Long pageSize, int maxSize) {
        long p = page == null ? 1 : page;
        long size = pageSize == null ? 10 : pageSize;
        if (p < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须从 1 开始");
        }
        if (size < 1 || size > maxSize) {
            throw new BizException(ErrorCode.BAD_REQUEST, "pageSize 必须在 1-" + maxSize + " 之间");
        }
        return new PageQuery(p, size);
    }
}
