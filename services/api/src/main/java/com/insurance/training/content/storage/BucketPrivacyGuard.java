package com.insurance.training.content.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetBucketPolicyArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 桶私有性守卫。
 *
 * <p>学员资料与课程视频不允许匿名访问。若桶策略允许匿名读（如误执行
 * {@code mc anonymous set download}），这里在启动时**直接拒绝启动**并说明原因，
 * 而不是等到线上被人批量拉走文件才发现。
 *
 * <p>判定标准：桶策略为空（无 policy）或 policy 中不存在 `"Principal":"*"` 的 Allow 语句。
 *
 * <p>在 {@code @PostConstruct} 中校验：此时所有单例已实例化但 Web 服务器尚未启动，
 * 校验失败会让上下文刷新失败，端口不会开放。不用 ApplicationRunner（在端口打开后才执行）
 * 也不用 SmartLifecycle（Spring Boot 的 WebServerStartStopLifecycle 使用最小 phase，
 * 任何自定义 phase 都排在它之后）。
 */
@Component
public class BucketPrivacyGuard {

    private static final Logger log = LoggerFactory.getLogger(BucketPrivacyGuard.class);

    private final MinioClient client;
    private final StorageProperties props;

    public BucketPrivacyGuard(MinioClient client, StorageProperties props) {
        this.client = client;
        this.props = props;
    }

    @PostConstruct
    void verifyOnStartup() {
        try {
            verify();
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("对象存储私有性校验失败，拒绝启动", ex);
        }
    }

    private void verify() throws Exception {
        String bucket = props.bucket();
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            // 开发便利：桶不存在时创建，但创建后仍要校验策略为私有
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.warn("私有桶 {} 不存在，已自动创建（策略默认私有）", bucket);
        }

        String policy = readPolicy(bucket);
        if (policy == null || policy.isBlank()) {
            log.info("桶 {} 无匿名访问策略，私有校验通过", bucket);
            return;
        }
        if (allowsAnonymous(policy)) {
            throw new IllegalStateException(
                    "桶 " + bucket + " 存在匿名访问策略（Principal=*），拒绝启动。"
                            + "请执行 `mc anonymous set none <alias>/" + bucket + "` 后重新启动。");
        }
        log.info("桶 {} 策略非匿名，私有校验通过", bucket);
    }


    private String readPolicy(String bucket) throws Exception {
        try {
            return client.getBucketPolicy(GetBucketPolicyArgs.builder().bucket(bucket).build());
        }
        catch (ErrorResponseException ex) {
            String code = ex.errorResponse().code();
            // NoSuchBucketPolicy：从未设置过策略，等价于私有
            if ("NoSuchBucketPolicy".equals(code)) {
                return null;
            }
            throw ex;
        }
    }

    /** 保守判定：策略中出现匿名 Principal 即视为不安全。 */
    private boolean allowsAnonymous(String policy) {
        String compact = policy.replaceAll("\\s+", "");
        return compact.contains("\"Principal\":\"*\"")
                || compact.contains("\"Principal\":{\"AWS\":\"*\"}")
                || compact.contains("\"Principal\":{\"AWS\":[\"*\"]}");
    }
}
