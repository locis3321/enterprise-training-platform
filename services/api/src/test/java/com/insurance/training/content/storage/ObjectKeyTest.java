package com.insurance.training.content.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 对象 key 生成规则：不能用原始文件名，必须是可预测的结构化路径。 */
class ObjectKeyTest {

    private final MinioStorageService service = new MinioStorageService(null, new StorageProperties(
            "http://127.0.0.1:9000", "ak", "sk", "training-private", null,
            Duration.ofMinutes(90), Duration.ofMinutes(10),
            DataSize.ofGigabytes(1), DataSize.ofMegabytes(50)));

    @Test
    @DisplayName("key 结构为 companyId/courseId/kind/uuid.ext")
    void keyStructure() {
        String key = service.buildObjectKey(7L, 42L, ResourceKind.VIDEO, "mp4");

        assertThat(key).startsWith("7/42/video/").endsWith(".mp4");
        String[] parts = key.split("/");
        assertThat(parts).hasSize(4);
        // 第三段必须是合法 UUID，证明没有把原始文件名拼进路径
        UUID.fromString(parts[3].replace(".mp4", ""));
    }

    @Test
    @DisplayName("同一输入生成不同 key（防覆盖）")
    void keysAreUnique() {
        String a = service.buildObjectKey(1L, 1L, ResourceKind.PDF, "pdf");
        String b = service.buildObjectKey(1L, 1L, ResourceKind.PDF, "pdf");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("视频 TTL 90 分钟、文件 10 分钟")
    void ttlByKind() {
        assertThat(service.ttl(ResourceKind.VIDEO)).isEqualTo(Duration.ofMinutes(90));
        assertThat(service.ttl(ResourceKind.PDF)).isEqualTo(Duration.ofMinutes(10));
        assertThat(service.ttl(ResourceKind.PPT)).isEqualTo(Duration.ofMinutes(10));
        assertThat(service.ttl(ResourceKind.IMAGE)).isEqualTo(Duration.ofMinutes(10));
    }
}
