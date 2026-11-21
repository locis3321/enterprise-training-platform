package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.storage.FileTypeDetector;
import com.insurance.training.content.storage.MinioStorageService;
import com.insurance.training.content.storage.ResourceKind;
import com.insurance.training.content.storage.StorageProperties;
import com.insurance.training.content.vo.ResourceVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * 资源上传。
 *
 * <p>上传只做「对象落盘 + 元数据入库」，不建立版本绑定（绑定在 {@link ResourceBindingService}）。
 * 这样资源可以被多个版本复用，也让「先传后编排」成为可能。
 *
 * <p>顺序很重要：先校验 → 再上传对象 → 再写库；写库失败必须删除已上传对象，
 * 否则会在桶里留下没有元数据的孤儿文件。
 */
@Service
public class ResourceUploadService {

    private static final Logger log = LoggerFactory.getLogger(ResourceUploadService.class);

    private final CourseService courseService;
    private final CourseResourceMapper resourceMapper;
    private final MinioStorageService storage;
    private final FileTypeDetector detector;
    private final StorageProperties storageProps;
    private final AuditService auditService;

    public ResourceUploadService(CourseService courseService, CourseResourceMapper resourceMapper,
                                 MinioStorageService storage, FileTypeDetector detector,
                                 StorageProperties storageProps, AuditService auditService) {
        this.courseService = courseService;
        this.resourceMapper = resourceMapper;
        this.storage = storage;
        this.detector = detector;
        this.storageProps = storageProps;
        this.auditService = auditService;
    }

    public ResourceVo upload(CurrentUser user, long courseId, ResourceKind declaredKind,
                             MultipartFile file, String ip) {
        Course course = courseService.getWritable(user, courseId);
        requireFile(file);

        // 先按声明大小做上限预检（避免对超大文件做 ZIP 解压等 CPU 密集检测）；
        // 真实类型检测在预检通过后再进行，并按检测出的类型再校验一次大小。
        assertSizeWithinUpperBound(file.getSize());

        FileTypeDetector.Detected detected;
        try (InputStream in = file.getInputStream()) {
            detected = detector.detect(in, null);
        }
        catch (BizException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件读取失败");
        }

        if (declaredKind != null && declaredKind != detected.kind()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "文件实际类型（" + detected.kind() + "）与声明类型（" + declaredKind + "）不一致");
        }
        assertSize(detected.kind(), file.getSize());

        String objectKey = storage.buildObjectKey(course.getInsuranceCompanyId(), courseId,
                detected.kind(), detected.ext());
        try (InputStream in = file.getInputStream()) {
            storage.put(objectKey, in, file.getSize(), detected.contentType());
        }
        catch (BizException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件上传失败");
        }

        CourseResource entity = new CourseResource();
        entity.setCourseId(courseId);
        entity.setInsuranceCompanyId(course.getInsuranceCompanyId());
        entity.setKind(detected.kind().name());
        entity.setObjectKey(objectKey);
        entity.setOriginalName(originalName(file));
        entity.setContentType(detected.contentType());
        entity.setSizeBytes(file.getSize());
        entity.setStatus("ACTIVE");
        entity.setCreatedByAdminId(user.id());

        try {
            resourceMapper.insert(entity);
        }
        catch (RuntimeException ex) {
            // 补偿：写库失败必须删除对象，避免孤儿文件
            storage.delete(objectKey);
            log.error("资源元数据写入失败，已回滚对象 key={}", objectKey, ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "资源保存失败，请重试");
        }

        auditService.record(user, "RESOURCE_UPLOADED", "course_resource", entity.getId(),
                "courseId=" + courseId + ",kind=" + detected.kind(), ip);
        return ResourceVo.of(entity);
    }

    private void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }
    }

    /** 预检：超过视频上限（最大允许值）直接拒绝，不进入内容检测。 */
    private void assertSizeWithinUpperBound(long size) {
        long maxAllowed = storageProps.maxVideoSize().toBytes();
        if (size > maxAllowed) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "文件超过大小上限（" + (maxAllowed / 1024 / 1024) + "MB）");
        }
    }

    private void assertSize(ResourceKind kind, long size) {
        long limit = kind == ResourceKind.VIDEO
                ? storageProps.maxVideoSize().toBytes()
                : storageProps.maxOtherSize().toBytes();
        if (size > limit) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "文件超过大小上限（" + (limit / 1024 / 1024) + "MB）");
        }
    }

    private String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        // 只保留文件名部分，去掉可能的路径（防路径穿越）
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
