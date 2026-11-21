package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.vo.AdminPackageDetailVo;
import com.insurance.training.training.vo.PackageCourseVo;
import com.insurance.training.training.vo.PackageVersionVo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 培训包详情：全部版本概要 + 指定版本的课程清单。 */
@Service
public class PackageDetailService {

    private final PackageService packageService;
    private final TrainingPackageVersionMapper versionMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseMapper courseMapper;
    private final CourseVersionMapper courseVersionMapper;

    public PackageDetailService(PackageService packageService, TrainingPackageVersionMapper versionMapper,
                                PackageCourseMapper packageCourseMapper, CourseMapper courseMapper,
                                CourseVersionMapper courseVersionMapper) {
        this.packageService = packageService;
        this.versionMapper = versionMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseMapper = courseMapper;
        this.courseVersionMapper = courseVersionMapper;
    }

    /** 指定版本（缺省 latest）；版本号不存在 → 404，不回落。 */
    public AdminPackageDetailVo adminDetail(CurrentUser user, long packageId, Integer versionNo) {
        TrainingPackage pkg = packageService.getVisible(user, packageId);
        int targetNo = versionNo != null ? versionNo : pkg.getLatestVersionNo();

        TrainingPackageVersion version = versionMapper.selectOne(Wrappers.<TrainingPackageVersion>lambdaQuery()
                .eq(TrainingPackageVersion::getPackageId, packageId)
                .eq(TrainingPackageVersion::getVersionNo, targetNo));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "版本不存在");
        }

        List<TrainingPackageVersion> versions = versionMapper.selectList(
                Wrappers.<TrainingPackageVersion>lambdaQuery()
                        .eq(TrainingPackageVersion::getPackageId, packageId)
                        .orderByDesc(TrainingPackageVersion::getVersionNo));
        List<Long> versionIds = versions.stream().map(TrainingPackageVersion::getId).toList();
        Map<Long, Long> counts = versionIds.isEmpty() ? Map.of()
                : packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .in(PackageCourse::getPackageVersionId, versionIds))
                .stream().collect(Collectors.groupingBy(PackageCourse::getPackageVersionId, Collectors.counting()));
        List<PackageVersionVo> versionVos = versions.stream()
                .map(v -> PackageVersionVo.of(v, counts.getOrDefault(v.getId(), 0L)))
                .toList();

        List<PackageCourse> links = packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                .eq(PackageCourse::getPackageVersionId, version.getId())
                .orderByAsc(PackageCourse::getSortNo));

        return new AdminPackageDetailVo(pkg.getId(), pkg.getInsuranceCompanyId(), pkg.getOwnerEnterpriseId(),
                pkg.getTitle(), pkg.getDescription(), pkg.getStatus(),
                pkg.getLatestVersionNo() == null ? 0 : pkg.getLatestVersionNo(), pkg.getPublishedVersionNo(),
                versionVos,
                new AdminPackageDetailVo.DetailVersion(version.getId(), version.getVersionNo(),
                        version.getTitle(), version.getDescription(), version.getStatus(), toCourseVos(links)));
    }

    private List<PackageCourseVo> toCourseVos(List<PackageCourse> links) {
        if (links.isEmpty()) {
            return List.of();
        }
        List<Long> courseVersionIds = links.stream().map(PackageCourse::getCourseVersionId).distinct().toList();
        Map<Long, CourseVersion> versionById = courseVersionMapper.selectList(
                        Wrappers.<CourseVersion>lambdaQuery().in(CourseVersion::getId, courseVersionIds))
                .stream().collect(Collectors.toMap(CourseVersion::getId, v -> v));
        List<Long> courseIds = versionById.values().stream().map(CourseVersion::getCourseId).distinct().toList();
        Map<Long, Course> courseById = courseIds.isEmpty() ? Map.of()
                : courseMapper.selectList(Wrappers.<Course>lambdaQuery().in(Course::getId, courseIds))
                .stream().collect(Collectors.toMap(Course::getId, c -> c));

        return links.stream().map(link -> {
            CourseVersion snapshot = versionById.get(link.getCourseVersionId());
            if (snapshot == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "包内课程版本缺失，数据异常：courseVersionId=" + link.getCourseVersionId());
            }
            Course course = courseById.get(snapshot.getCourseId());
            if (course == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "包内课程已被删除，数据异常：courseId=" + snapshot.getCourseId());
            }
            return new PackageCourseVo(snapshot.getId(), course.getId(), course.getTitle(),
                    snapshot.getVersionNo(), snapshot.getTitle(), course.getStatus(),
                    link.getSortNo() == null ? 0 : link.getSortNo());
        }).toList();
    }
}