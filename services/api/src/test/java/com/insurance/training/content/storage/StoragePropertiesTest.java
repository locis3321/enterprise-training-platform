package com.insurance.training.content.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象 key 与服务端配置的最小契约测试。
 *
 * <p>不启动 MinIO，仅验证不依赖网络的纯逻辑：key 结构、TTL 选择、公网地址回退。
 */
class StoragePropertiesTest {

    private StorageProperties props(String publicEndpoint) {
        return new StorageProperties(
                "http://127.0.0.1:9000",
                "ak", "sk", "training-private",
                publicEndpoint,
                Duration.ofMinutes(90),
                Duration.ofMinutes(10),
                DataSize.ofGigabytes(1),
                DataSize.ofMegabytes(50));
    }

    @Test
    @DisplayName("未配置公网地址时回退为服务端地址")
    void publicEndpointFallsBackToInternal() {
        assertThat(props(null).effectivePublicEndpoint()).isEqualTo("http://127.0.0.1:9000");
        assertThat(props("  ").effectivePublicEndpoint()).isEqualTo("http://127.0.0.1:9000");
    }

    @Test
    @DisplayName("配置了公网地址时使用公网地址")
    void publicEndpointOverrides() {
        assertThat(props("https://cdn.example.com").effectivePublicEndpoint())
                .isEqualTo("https://cdn.example.com");
    }

    @Test
    @DisplayName("视频与其他资料的有效期分别配置")
    void ttlDiffersByKind() {
        StorageProperties p = props(null);

        assertThat(p.presignTtlVideo()).isEqualTo(Duration.ofMinutes(90));
        assertThat(p.presignTtlFile()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("上传上限：视频 1GB、其他 50MB")
    void sizeLimits() {
        StorageProperties p = props(null);

        assertThat(p.maxVideoSize().toBytes()).isEqualTo(1024L * 1024 * 1024);
        assertThat(p.maxOtherSize().toBytes()).isEqualTo(50L * 1024 * 1024);
    }
}
