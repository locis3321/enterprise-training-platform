package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.training.dto.PackageVersionRequest;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.vo.PackageVersionVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 培训包版本生命周期：新建、修改、发布、复制。发布后快照不可变。 */
@Service
public class PackageVersionService {

    private final PackageService packageService;
    private final TrainingPackageMapper packageMapper;
    private final TrainingPackageVersionMapper versionMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final AuditService auditService;

    public PackageVersionService(PackageService packageService, TrainingPackageMapper packageMapper,
                                 TrainingPackageVersionMapper versionMapper, PackageCourseMapper packageCourseMapper,
                                 AuditService auditService) {
        this.packageService = packageService;
        this.packageMapper = packageMapper;
        this.versionMapper = versionMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.auditService = auditService;
    }

    public List<PackageVersionVo> list(CurrentUser user, long packageId) {
        packageService.getVisible(user, packageId);
        List<TrainingPackageVersion> versions = versionMapper.selectList(
                Wrappers.<TrainingPackageVersion>lambdaQuery()
                        .eq(TrainingPackageVersion::getPackageId, packageId)
                        .orderByDesc(TrainingPackageVersion::getVersionNo));
        if (versions.isEmpty()) {
            return List.of();
        }
        List<Long> versionIds = versions.stream().map(TrainingPackageVersion::getId).toList();
        Map<Long, Long> counts = packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .in(PackageCourse::getPackageVersionId, versionIds))
                .stream().collect(Collectors.groupingBy(PackageCourse::getPackageVersionId, Collectors.counting()));
        return versions.stream()
                .map(v -> PackageVersionVo.of(v, counts.getOrDefault(v.getId(), 0L)))
                .toList();
    }

    /** 读取版本并校验属于该包；版本号不存在返回 404，不回落最新。 */
    public TrainingPackageVersion getOwnedVersion(long packageId, int versionNo) {
        TrainingPackageVersion version = versionMapper.selectOne(Wrappers.<TrainingPackageVersion>lambdaQuery()
                .eq(TrainingPackageVersion::getPackageId, packageId)
                .eq(TrainingPackageVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "版本不存在");
        }
        return version;
    }

    /** 可写校验 + 版本必须为 DRAFT。 */
    public TrainingPackageVersion getWritableDraft(CurrentUser user, long packageId, int versionNo) {
        packageService.getWritable(user, packageId);
        TrainingPackageVersion version = getOwnedVersion(packageId, versionNo);
        if (!"DRAFT".equals(version.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "已发布版本不可修改，请复制为新版本后再改");
        }
        return version;
    }

    /** 新建草稿版本：version_no 原子递增。 */
    @Transactional
    public PackageVersionVo create(CurrentUser user, long packageId, PackageVersionRequest request, String ip) {
        TrainingPackage pkg = packageService.getWritable(user, packageId);
        int nextNo = bumpLatestVersion(pkg);

        TrainingPackageVersion version = new TrainingPackageVersion();
        version.setPackageId(packageId);
        version.setVersionNo(nextNo);
        version.setTitle(request != null && request.title() != null && !request.title().isBlank()
                ? request.title().trim() : pkg.getTitle());
        version.setDescription(request != null && request.description() != null
                ? request.description() : pkg.getDescription());
        version.setStatus("DRAFT");
        insertVersion(version);

        auditService.record(user, "PACKAGE_VERSION_CREATED", "training_package_version", version.getId(),
                "packageId=" + packageId + ",versionNo=" + nextNo, ip);
        return PackageVersionVo.of(version, 0);
    }

    /** 修改草稿标题/简介：条件更新，仅 DRAFT 生效。 */
    @Transactional
    public PackageVersionVo update(CurrentUser user, long packageId, int versionNo,
                                   PackageVersionRequest request, String ip) {
        TrainingPackageVersion version = getWritableDraft(user, packageId, versionNo);
        String newTitle = request.title() == null || request.title().isBlank()
                ? version.getTitle() : request.title().trim();
        String newDescription = request.description() == null
                ? version.getDescription() : request.description();
        int updated = versionMapper.update(null, Wrappers.<TrainingPackageVersion>lambdaUpdate()
                .set(TrainingPackageVersion::getTitle, newTitle)
                .set(TrainingPackageVersion::getDescription, newDescription)
                .eq(TrainingPackageVersion::getId, version.getId())
                .eq(TrainingPackageVersion::getStatus, "DRAFT"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，修改未生效");
        }
        version.setTitle(newTitle);
        version.setDescription(newDescription);
        auditService.record(user, "PACKAGE_VERSION_UPDATED", "training_package_version", version.getId(),
                "packageId=" + packageId + ",versionNo=" + versionNo, ip);
        return PackageVersionVo.of(version, countCourses(version.getId()));
    }

    /**
     * 发布版本（同事务）。
     *
     * <p>校验：DRAFT、至少 1 门课程、不得早于当前生效版本。
     * 更新：版本状态 CAS + 包指针 CAS（指针不回退），任一失败整个事务回滚。
     * 回滚只能走 copy → 改 → publish。
     */
    @Transactional
    public PackageVersionVo publish(CurrentUser user, long packageId, int versionNo, String ip) {
        TrainingPackage pkg = packageService.getWritable(user, packageId);
        TrainingPackageVersion version = getOwnedVersion(packageId, versionNo);
        if (!"DRAFT".equals(version.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已发布，不能重复发布");
        }
        if (pkg.getPublishedVersionNo() != null && versionNo < pkg.getPublishedVersionNo()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "不能发布早于当前生效版本的历史版本；如需回滚请复制该版本后重新发布");
        }
        long courseCount = countCourses(version.getId());
        if (courseCount == 0) {
            throw new BizException(ErrorCode.CONFLICT, "培训包版本没有关联任何课程，不能发布");
        }

        int versionUpdated = versionMapper.update(null, Wrappers.<TrainingPackageVersion>lambdaUpdate()
                .set(TrainingPackageVersion::getStatus, "PUBLISHED")
                .set(TrainingPackageVersion::getPublishedAt, LocalDateTime.now())
                .set(TrainingPackageVersion::getPublishedByAdminId, user.id())
                .eq(TrainingPackageVersion::getId, version.getId())
                .eq(TrainingPackageVersion::getStatus, "DRAFT"));
        if (versionUpdated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被其他操作发布");
        }

        int packageUpdated = packageMapper.update(null, Wrappers.<TrainingPackage>lambdaUpdate()
                .set(TrainingPackage::getStatus, "PUBLISHED")
                .set(TrainingPackage::getPublishedVersionNo, versionNo)
                .eq(TrainingPackage::getId, pkg.getId())
                .and(w -> w.isNull(TrainingPackage::getPublishedVersionNo)
                        .or().le(TrainingPackage::getPublishedVersionNo, versionNo)));
        if (packageUpdated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "培训包生效版本已被更新，请刷新后重试");
        }
        version.setStatus("PUBLISHED");

        auditService.record(user, "PACKAGE_VERSION_PUBLISHED", "training_package_version", version.getId(),
                "packageId=" + packageId + ",versionNo=" + versionNo + ",courseCount=" + courseCount, ip);
        return PackageVersionVo.of(version, courseCount);
    }

    /** 复制版本为新草稿：深复制 package_course 行（指向同一课程版本，不复制课程内容）。 */
    @Transactional
    public PackageVersionVo copy(CurrentUser user, long packageId, int versionNo, String ip) {
        TrainingPackage pkg = packageService.getWritable(user, packageId);
        TrainingPackageVersion source = getOwnedVersion(packageId, versionNo);
        int nextNo = bumpLatestVersion(pkg);

        TrainingPackageVersion target = new TrainingPackageVersion();
        target.setPackageId(packageId);
        target.setVersionNo(nextNo);
        target.setTitle(source.getTitle());
        target.setDescription(source.getDescription());
        target.setStatus("DRAFT");
        insertVersion(target);

        List<PackageCourse> links = packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                .eq(PackageCourse::getPackageVersionId, source.getId())
                .orderByAsc(PackageCourse::getSortNo));
        for (PackageCourse link : links) {
            PackageCourse copy = new PackageCourse();
            copy.setPackageVersionId(target.getId());
            copy.setCourseVersionId(link.getCourseVersionId());
            copy.setSortNo(link.getSortNo());
            packageCourseMapper.insert(copy);
        }

        auditService.record(user, "PACKAGE_VERSION_COPIED", "training_package_version", target.getId(),
                "packageId=" + packageId + ",from=" + versionNo + ",to=" + nextNo + ",courses=" + links.size(), ip);
        return PackageVersionVo.of(target, links.size());
    }

    private void insertVersion(TrainingPackageVersion version) {
        try {
            versionMapper.insert(version);
        }
        catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }
    }

    private long countCourses(long packageVersionId) {
        Long count = packageCourseMapper.selectCount(Wrappers.<PackageCourse>lambdaQuery()
                .eq(PackageCourse::getPackageVersionId, packageVersionId));
        return count == null ? 0 : count;
    }

    /** 原子递增 latest_version_no：条件更新，影响 0 行说明并发冲突。 */
    private int bumpLatestVersion(TrainingPackage pkg) {
        Integer current = pkg.getLatestVersionNo();
        if (current == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "培训包版本号缺失，数据异常");
        }
        int next = current + 1;
        int updated = packageMapper.update(null, Wrappers.<TrainingPackage>lambdaUpdate()
                .set(TrainingPackage::getLatestVersionNo, next)
                .eq(TrainingPackage::getId, pkg.getId())
                .eq(TrainingPackage::getLatestVersionNo, current));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "版本号并发冲突，请重试");
        }
        pkg.setLatestVersionNo(next);
        return next;
    }
}