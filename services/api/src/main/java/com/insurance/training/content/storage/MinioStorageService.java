package com.insurance.training.content.storage;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.content.storage.ResourceKind;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Http;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 私有存储适配。
 *
 * <p>约定：
 * <ul>
 *   <li>对象 key 由服务端生成，禁止使用原始文件名（防路径穿越与编码问题）；</li>
 *   <li>下载地址一律短时签名，不落库、不在日志打印；</li>
 *   <li>视频与其他资料的有效期不同（视频需覆盖 1 小时连续播放）。</li>
 * </ul>
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient client;
    private final StorageProperties props;
    /**
     * 用于签名的客户端。
     *
     * <p>SigV4 的签名包含 Host，因此**不能**先签名再替换域名——替换后签名必然不匹配
     * （SignatureDoesNotMatch）。正确做法是用「客户端可达的端点」构造客户端来签名。
     * 当 publicEndpoint 未配置或与服务端端点相同时，直接复用服务端 client。
     */
    private final MinioClient presignClient;

    public MinioStorageService(MinioClient client, StorageProperties props) {
        this.client = client;
        this.props = props;
        String publicEndpoint = props.effectivePublicEndpoint();
        boolean same = publicEndpoint == null || publicEndpoint.isBlank()
                || publicEndpoint.equals(props.endpoint());
        this.presignClient = same ? client : MinioClient.builder()
                .endpoint(publicEndpoint)
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    /**
     * 生成对象 key：{companyId}/{courseId}/{kind}/{uuid}.{ext}。
     *
     * <p>不以原始文件名参与拼接，避免中文/特殊字符与路径穿越；扩展名由调用方传入，
     * 且必须是已通过类型校验的白名单值。
     */
    public String buildObjectKey(long companyId, long courseId, ResourceKind kind, String ext) {
        return companyId + "/" + courseId + "/" + kind.name().toLowerCase() + "/"
                + java.util.UUID.randomUUID() + "." + ext;
    }

    /** 上传对象。调用方负责类型/大小校验与失败补偿（写库失败后调用 {@link #delete}）。 */
    public void put(String objectKey, InputStream stream, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .stream(stream, size, -1L)
                    .contentType(contentType)
                    .build());
        }
        catch (Exception ex) {
            log.error("对象上传失败 key={}", objectKey, ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件上传失败，请重试");
        }
    }

    /** 删除对象；对象不存在不视为错误（幂等）。 */
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(objectKey)
                    .build());
        }
        catch (Exception ex) {
            // 补偿动作失败不能掩盖主流程错误，仅记录以便人工清理孤儿对象
            log.error("补偿删除失败，需人工清理孤儿对象 key={}", objectKey, ex);
        }
    }

    /**
     * 生成签名下载地址。
     *
     * @param kind 决定有效期：视频 90 分钟覆盖 1 小时播放；其他 10 分钟
     * @param downloadName 为 null 时以 inline 返回（视频可在线播放），否则按附件下载
     */
    public String presignedGetUrl(String objectKey, ResourceKind kind, String downloadName) {
        try {
            var builder = GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(props.bucket())
                    .object(objectKey)
                    .expiry((int) ttl(kind).toMinutes(), TimeUnit.MINUTES);
            if (downloadName != null && !downloadName.isBlank()) {
                builder.extraQueryParams(java.util.Map.of(
                        "response-content-disposition", "attachment; filename=\"" + downloadName + "\""));
            }
            // 用客户端可达端点签名，保证 Host 参与签名计算
            return presignClient.getPresignedObjectUrl(builder.build());
        }
        catch (Exception ex) {
            log.error("签名 URL 生成失败 key={}", objectKey, ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法生成下载地址");
        }
    }

    public java.time.Duration ttl(ResourceKind kind) {
        return kind == ResourceKind.VIDEO ? props.presignTtlVideo() : props.presignTtlFile();
    }


}
