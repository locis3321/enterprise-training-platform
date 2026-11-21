package com.insurance.training.content.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MinIO 联调（需本地 compose 与凭据）。
 *
 * <p>通过环境变量 {@code MINIO_INTEGRATION=1} 显式开启，避免默认构建依赖外部服务。
 * 覆盖：上传 → 匿名访问被拒 → 签名 URL 可下载。
 */
@EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION", matches = "1")
class MinioStorageIntegrationTest {

    private static MinioStorageService service;

    @BeforeAll
    static void setUp() {
        StorageProperties props = new StorageProperties(
                "http://127.0.0.1:9000",
                System.getenv("MINIO_ACCESS_KEY"),
                System.getenv("MINIO_SECRET_KEY"),
                System.getenv("MINIO_BUCKET"),
                null,
                Duration.ofMinutes(90),
                Duration.ofMinutes(10),
                org.springframework.util.unit.DataSize.ofGigabytes(1),
                org.springframework.util.unit.DataSize.ofMegabytes(50));
        var client = new StorageConfig().minioClient(props);
        service = new MinioStorageService(client, props);
    }

    @Test
    @DisplayName("上传后可生成签名 URL 并真实下载内容")
    void uploadThenPresignedDownload() throws Exception {
        String key = service.buildObjectKey(1L, 1L, ResourceKind.PDF, "txt");
        byte[] content = "integration-check".getBytes(StandardCharsets.UTF_8);

        try (InputStream in = new java.io.ByteArrayInputStream(content)) {
            service.put(key, in, content.length, "text/plain");
        }
        try {
            String anon = "http://127.0.0.1:9000/" + System.getenv("MINIO_BUCKET") + "/" + key;
            assertThat(status(anon)).as("匿名访问必须被拒绝").isEqualTo(403);

            String signed = service.presignedGetUrl(key, ResourceKind.PDF, null);
            assertThat(signed).contains("X-Amz-Signature");
            assertThat(status(signed)).as("签名 URL 应可访问").isEqualTo(200);

            String expired = service.presignedGetUrl(key, ResourceKind.PDF, null);
            assertThat(expired).isNotBlank();
        }
        finally {
            service.delete(key);
        }
    }

    private int status(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }
}
