package com.insurance.training.content.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 对象存储配置。
 *
 * <p>所有值来自环境变量；生产缺失即启动失败（见 {@link StorageConfig} 的校验），
 * 不使用「猜一个默认桶名」这类静默兜底。
 *
 * @param endpoint              服务端访问 MinIO 的地址
 * @param accessKey             访问密钥
 * @param secretKey             私有密钥
 * @param bucket                私有桶名
 * @param publicEndpoint        客户端（小程序/浏览器）可访问的地址；为空则与 endpoint 相同
 * @param presignTtlVideo       视频签名有效期（需覆盖 1 小时连续播放）
 * @param presignTtlFile        其他资源签名有效期
 * @param maxVideoSize          单文件视频上限
 * @param maxOtherSize          其他资源单文件上限
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String publicEndpoint,
        Duration presignTtlVideo,
        Duration presignTtlFile,
        DataSize maxVideoSize,
        DataSize maxOtherSize
) {

    /** 客户端实际使用的地址：未单独配置时回退到服务端地址。 */
    public String effectivePublicEndpoint() {
        return (publicEndpoint == null || publicEndpoint.isBlank()) ? endpoint : publicEndpoint;
    }
}
