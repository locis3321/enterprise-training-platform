package com.insurance.training.system.vo;

import java.time.LocalDateTime;

/**
 * 隐私协议视图。
 *
 * <p>{@code url} 是每次请求重新生成的短期签名地址（实时获取，不缓存）；
 * {@code available=false} 表示尚未上传，客户端应显示「暂未发布」而非报错。
 */
public record PrivacyPolicyVo(
        boolean available,
        String filename,
        LocalDateTime updatedAt,
        String url) {
}
