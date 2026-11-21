package com.insurance.training.system.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.storage.MinioStorageService;
import com.insurance.training.content.storage.ResourceKind;
import com.insurance.training.system.config.SystemConfigKey;
import com.insurance.training.system.vo.PrivacyPolicyVo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 隐私协议 PDF。
 *
 * <p>存储走既有 MinIO 私有桶；配置表只存对象键。学员端每次请求都重新签名，
 * 因此拿到的是**实时**地址（10 分钟有效），管理端替换文件后无需客户端发版。
 *
 * <p>对象键带时间戳：新旧版本共存，替换失败不会把线上协议指空；旧对象保留可回溯。
 */
@Service
public class PrivacyPolicyService {

    /** 协议文档上限 10MB：纯文本 PDF 远小于此，超限基本是误传视频。 */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String DISPLAY_NAME = "隐私协议.pdf";

    private final MinioStorageService storage;
    private final SystemConfigService config;
    private final AuditService auditService;

    public PrivacyPolicyService(MinioStorageService storage, SystemConfigService config,
                                AuditService auditService) {
        this.storage = storage;
        this.config = config;
        this.auditService = auditService;
    }

    /**
     * 学员端读取（免登录）。
     *
     * <p>未上传时返回 {@code available=false} 而不是 404：客户端据此显示「暂未发布」，
     * 不必把「没有协议」当成错误。
     */
    public PrivacyPolicyVo current() {
        String objectKey = config.getString(SystemConfigKey.LEGAL_PRIVACY_POLICY);
        if (objectKey == null || objectKey.isBlank()) {
            return new PrivacyPolicyVo(false, null, null, null);
        }
        LocalDateTime updatedAt = config.overriddenAt(SystemConfigKey.LEGAL_PRIVACY_POLICY);
        // downloadName 传 null → inline 返回，便于客户端直接打开而不是强制下载
        String url = storage.presignedGetUrl(objectKey, ResourceKind.PDF, null);
        return new PrivacyPolicyVo(true, DISPLAY_NAME, updatedAt, url);
    }

    /** 管理端上传替换。校验魔数而非只看扩展名，避免把任意文件当协议发布。 */
    public PrivacyPolicyVo upload(CurrentUser actor, MultipartFile file, String ip) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的 PDF 文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "隐私协议 PDF 不能超过 10MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件读取失败，请重试");
        }
        if (!isPdf(bytes)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只支持 PDF 文件（文件头不是 %PDF-）");
        }

        String objectKey = "legal/privacy-policy/" + LocalDateTime.now().format(STAMP) + ".pdf";
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            storage.put(objectKey, in, bytes.length, "application/pdf");
        }
        catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件上传失败，请重试");
        }

        try {
            config.setManagedValue(actor, SystemConfigKey.LEGAL_PRIVACY_POLICY, objectKey, ip);
        }
        catch (RuntimeException ex) {
            // 配置写失败则补偿删除，避免留下无人引用的孤儿对象
            storage.delete(objectKey);
            throw ex;
        }
        auditService.record(actor, "PRIVACY_POLICY_UPLOADED", "sys_config", null,
                "objectKey=" + objectKey + ", bytes=" + bytes.length, ip);
        return current();
    }

    /** PDF 魔数：%PDF-（前 5 字节）。 */
    private static boolean isPdf(byte[] bytes) {
        if (bytes.length < 5) {
            return false;
        }
        return bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44
                && bytes[3] == 0x46 && bytes[4] == 0x2D;
    }
}
