package com.insurance.training.content.storage;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。
 *
 * <p>启动时对必填项做显式校验：endpoint / accessKey / secretKey / bucket 任一为空直接失败，
 * 避免因配置遗漏在运行期才暴露（且可能写进错误的桶）。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public MinioClient minioClient(StorageProperties props) {
        require(props.endpoint(), "MINIO_ENDPOINT");
        require(props.accessKey(), "MINIO_ACCESS_KEY");
        require(props.secretKey(), "MINIO_SECRET_KEY");
        require(props.bucket(), "MINIO_BUCKET");
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    private void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("对象存储配置缺失：" + name + "，拒绝启动");
        }
    }
}
