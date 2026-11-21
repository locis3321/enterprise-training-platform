package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.training.dto.PackageCourseRequest;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 培训包版本关联课程版本。
 *
 * <p>关联是引用而非复制：包快照保存 {@code course_version_id}，课程改版不影响已发布的包。
 * 所有写入都用 {@link PackageCourseMapper} 的条件语句，与「版本仍为 DRAFT」在同一语句内判定。
 */
@Service
public class PackageCourseService {

    private final PackageService packageService;
    private final PackageVersionService versionService;
    private final TrainingPackageVersionMapper versionMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseMapper courseMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final AuditService auditService;

    public PackageCourseService(PackageService packageService, PackageVersionService versionService,
                                TrainingPackageVersionMapper versionMapper, PackageCourseMapper packageCourseMapper,
                                CourseMapper courseMapper, CourseVersionMapper courseVersionMapper,
                                AuditService auditService) {
        this.packageService = packageService;
        this.versionService = versionService;
        this.versionMapper = versionMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseMapper = courseMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.auditService = auditService;
    }

    /**
     * 关联课程版本：仅 DRAFT 包版本；课程版本必须已发布且同集团。
     *
     * <p>重复关联幂等，并按新 {@code sortNo} 条件更新，因此重排无需先解绑。
     */
    @Transactional
    public void bind(CurrentUser user, long packageId, int versionNo, PackageCourseRequest request, String ip) {
        TrainingPackage pkg = packageService.getWritable(user, packageId);
        TrainingPackageVersion version = versionService.getWritableDraft(user, packageId, versionNo);
        requirePublishedCourseVersion(pkg, request.courseVersionId());

        PackageCourse existing = packageCourseMapper.selectOne(Wrappers.<PackageCourse>lambdaQuery()
                .eq(PackageCourse::getPackageVersionId, version.getId())
                .eq(PackageCourse::getCourseVersionId, request.courseVersionId()));
        if (existing != null) {
            int updated = packageCourseMapper.updateSortNoIfDraft(existing.getId(), request.sortNo());
            if (updated == 0) {
                throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，排序未生效");
            }
            auditService.record(user, "PACKAGE_COURSE_REORDERED", "training_package_version", version.getId(),
                    "packageId=" + packageId + ",versionNo=" + versionNo
                            + ",courseVersionId=" + request.courseVersionId() + ",sortNo=" + request.sortNo(), ip);
            return;
        }

        int inserted;
        try {
            inserted = packageCourseMapper.insertIfDraft(version.getId(), request.courseVersionId(), request.sortNo());
        }
        catch (DuplicateKeyException ex) {
            // 并发下另一请求已插入同一关联，视为幂等成功
            return;
        }
        if (inserted == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，关联未生效");
        }
        auditService.record(user, "PACKAGE_COURSE_BOUND", "training_package_version", version.getId(),
                "packageId=" + packageId + ",versionNo=" + versionNo
                        + ",courseVersionId=" + request.courseVersionId() + ",sortNo=" + request.sortNo(), ip);
    }

    /** 解除关联：仅 DRAFT；不存在该关联返回 404，被并发发布则 409。不删除课程本身。 */
    @Transactional
    public void unbind(CurrentUser user, long packageId, int versionNo, long courseVersionId, String ip) {
        packageService.getWritable(user, packageId);
        TrainingPackageVersion version = versionService.getWritableDraft(user, packageId, versionNo);

        int deleted = packageCourseMapper.deleteIfDraft(version.getId(), courseVersionId);
        if (deleted == 0) {
            TrainingPackageVersion current = versionMapper.selectById(version.getId());
            if (current == null || !"DRAFT".equals(current.getStatus())) {
                throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，解除关联未生效");
            }
            throw new BizException(ErrorCode.NOT_FOUND, "该课程版本未关联到此培训包版本");
        }
        auditService.record(user, "PACKAGE_COURSE_UNBOUND", "training_package_version", version.getId(),
                "packageId=" + packageId + ",versionNo=" + versionNo + ",courseVersionId=" + courseVersionId, ip);
    }

    /** 课程版本必须存在、同公司、已发布。跨公司在状态之前判定，避免泄露他公司课程的状态。 */
    private void requirePublishedCourseVersion(TrainingPackage pkg, long courseVersionId) {
        CourseVersion courseVersion = courseVersionMapper.selectById(courseVersionId);
        if (courseVersion == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程版本不存在");
        }
        Course course = courseMapper.selectById(courseVersion.getCourseId());
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        if (!pkg.getInsuranceCompanyId().equals(course.getInsuranceCompanyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能关联其他集团的课程");
        }
        if (!"PUBLISHED".equals(courseVersion.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只能关联已发布的课程版本，草稿课程不能进包");
        }
    }
}