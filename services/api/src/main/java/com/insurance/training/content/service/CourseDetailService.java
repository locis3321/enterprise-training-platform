package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.web.PageQuery;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import com.insurance.training.content.vo.AdminCourseDetailVo;
import com.insurance.training.content.vo.CourseVo;
import com.insurance.training.content.vo.MiniappCourseDetailVo;
import com.insurance.training.content.vo.ResourceVo;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.employee.entity.Employee;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 课程详情：管理端可指定版本，学员固定已发布版本。 */
@Service
public class CourseDetailService {

    private final CourseService courseService;
    private final CourseMapper courseMapper;
    private final CourseVersionMapper versionMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final LearnerGuard learnerGuard;
    private final int maxPageSize;

    public CourseDetailService(CourseService courseService, CourseMapper courseMapper,
                               CourseVersionMapper versionMapper, CourseSectionMapper sectionMapper,
                               CourseResourceMapper resourceMapper,
                               CourseVersionResourceMapper bindingMapper,
                               LearnerGuard learnerGuard,
                               @Value("${app.page.max-size:200}") int maxPageSize) {
        this.courseService = courseService;
        this.courseMapper = courseMapper;
        this.versionMapper = versionMapper;
        this.sectionMapper = sectionMapper;
        this.resourceMapper = resourceMapper;
        this.bindingMapper = bindingMapper;
        this.learnerGuard = learnerGuard;
        this.maxPageSize = maxPageSize;
    }

    // ---------------- 管理端 ----------------

    /** 管理端详情：指定版本（缺省取 latest）；版本号不存在 → 404。 */
    public AdminCourseDetailVo adminDetail(CurrentUser user, long courseId, Integer versionNo) {
        Course course = courseService.getVisible(user, courseId);
        int targetNo = versionNo != null ? versionNo : course.getLatestVersionNo();

        CourseVersion version = versionMapper.selectOne(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .eq(CourseVersion::getVersionNo, targetNo));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "版本不存在");
        }

        List<CourseSection> sections = sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, version.getId())
                .orderByAsc(CourseSection::getSortNo));
        List<CourseVersionResource> bindings = bindingMapper.selectList(
                Wrappers.<CourseVersionResource>lambdaQuery()
                        .eq(CourseVersionResource::getCourseVersionId, version.getId())
                        .orderByAsc(CourseVersionResource::getSortNo));

        Map<Long, CourseResource> resourceById = loadResources(bindings);
        Map<Long, List<CourseVersionResource>> bindingsBySection = bindings.stream()
                .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));

        List<AdminCourseDetailVo.DetailSection> detailSections = new ArrayList<>();
        for (CourseSection section : sections) {
            List<AdminCourseDetailVo.SectionResource> items = new ArrayList<>();
            for (CourseVersionResource b : bindingsBySection.getOrDefault(section.getId(), List.of())) {
                CourseResource r = resourceById.get(b.getResourceId());
                if (r == null) {
                    continue;
                }
                items.add(new AdminCourseDetailVo.SectionResource(r.getId(), r.getKind(),
                        r.getOriginalName(), r.getSizeBytes() == null ? 0 : r.getSizeBytes(),
                        b.getSortNo() == null ? 0 : b.getSortNo()));
            }
            detailSections.add(new AdminCourseDetailVo.DetailSection(section.getId(), section.getTitle(),
                    section.getSortNo(), section.getDurationSeconds(), items));
        }

        // 未绑定到「当前查看版本」的资源（可能绑定在其他版本），便于管理员继续编排
        List<CourseResource> all = resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery()
                .eq(CourseResource::getCourseId, courseId)
                .eq(CourseResource::getStatus, "ACTIVE"));
        List<Long> boundIds = bindingMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                        .eq(CourseVersionResource::getCourseVersionId, version.getId()))
                .stream().map(CourseVersionResource::getResourceId).toList();
        List<ResourceVo> unbound = all.stream()
                .filter(r -> !boundIds.contains(r.getId()))
                .map(ResourceVo::of)
                .toList();

        return new AdminCourseDetailVo(course.getId(), course.getTitle(), course.getDescription(),
                course.getStatus(), course.getCoverResourceId(),
                course.getLatestVersionNo() == null ? 0 : course.getLatestVersionNo(),
                course.getPublishedVersionNo(),
                new AdminCourseDetailVo.DetailVersion(version.getId(), version.getVersionNo(),
                        version.getTitle(), version.getDescription(), version.getStatus(), detailSections),
                unbound);
    }

    // ---------------- 学员 ----------------

    /** 学员可见课程列表：同公司、已发布。 */
    public PageResult<CourseVo> miniappCourses(CurrentUser user, Long page, Long pageSize) {
        LearnerContext learner = requireLearner(user);
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);

        // 跨子公司学员：取其名下员工所属的全部集团
        List<Long> companyIds = learner.employees().stream()
                .map(Employee::getInsuranceCompanyId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (companyIds.isEmpty()) {
            return PageResult.empty(pq.page(), pq.pageSize());
        }
        var wrapper = Wrappers.<Course>lambdaQuery()
                .in(Course::getInsuranceCompanyId, companyIds)
                .eq(Course::getStatus, "PUBLISHED")
                .isNotNull(Course::getPublishedVersionNo)
                .orderByDesc(Course::getId);
        var result = courseMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                pq.page(), pq.pageSize()), wrapper);
        List<CourseVo> items = result.getRecords().stream().map(CourseVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    /** 学员课程详情：固定已发布版本；未发布/已下线 → 404。 */
    public MiniappCourseDetailVo miniappDetail(CurrentUser user, long courseId) {
        LearnerContext learner = requireLearner(user);
        Course course = courseMapper.selectById(courseId);
        if (course == null || !"PUBLISHED".equals(course.getStatus())
                || course.getPublishedVersionNo() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在或未开放");
        }
        if (!belongsToCompany(learner, course.getInsuranceCompanyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的课程");
        }
        CourseVersion version = versionMapper.selectOne(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .eq(CourseVersion::getVersionNo, course.getPublishedVersionNo()));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程版本不存在");
        }

        List<CourseSection> sections = sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, version.getId())
                .orderByAsc(CourseSection::getSortNo));
        List<CourseVersionResource> bindings = bindingMapper.selectList(
                Wrappers.<CourseVersionResource>lambdaQuery()
                        .eq(CourseVersionResource::getCourseVersionId, version.getId())
                        .orderByAsc(CourseVersionResource::getSortNo));
        Map<Long, CourseResource> resourceById = loadResources(bindings);
        Map<Long, List<CourseVersionResource>> bySection = bindings.stream()
                .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));

        List<MiniappCourseDetailVo.Section> items = sections.stream()
                .map(s -> {
                    List<MiniappCourseDetailVo.ResourceBrief> briefs = bySection
                            .getOrDefault(s.getId(), List.of()).stream()
                            .map(b -> resourceById.get(b.getResourceId()))
                            .filter(r -> r != null && "ACTIVE".equals(r.getStatus()))
                            .map(r -> new MiniappCourseDetailVo.ResourceBrief(r.getId(), r.getKind(),
                                    r.getOriginalName(),
                                    r.getSizeBytes() == null ? 0 : r.getSizeBytes()))
                            .toList();
                    return new MiniappCourseDetailVo.Section(s.getId(), s.getTitle(), s.getSortNo(),
                            s.getDurationSeconds(), briefs.size(), briefs);
                })
                .toList();
        return new MiniappCourseDetailVo(course.getId(), version.getTitle(), version.getDescription(),
                version.getVersionNo(), items.size(), items);
    }

    /**
     * 解析当前学员。
     *
     * <p>**不能用 {@code user.id()} 查 employee**：JWT 的 sub 是 learner_account.id，
     * 与 employee.id 是两套自增序列，混用会对存量学员误判 403，更糟的是 ID 碰撞时
     * 会按无关员工的归属授权（越权）。统一走 {@link LearnerGuard}。
     */
    private LearnerContext requireLearner(CurrentUser user) {
        return learnerGuard.requireLearner(user);
    }

    /** 该学员名下是否有任一员工属于此集团（同集团可见）。 */
    private boolean belongsToCompany(LearnerContext learner, Long companyId) {
        return learner.employees().stream()
                .anyMatch(e -> companyId != null && companyId.equals(e.getInsuranceCompanyId()));
    }

    private Map<Long, CourseResource> loadResources(List<CourseVersionResource> bindings) {
        if (bindings.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = bindings.stream().map(CourseVersionResource::getResourceId).distinct().toList();
        return resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery()
                        .in(CourseResource::getId, ids))
                .stream().collect(Collectors.toMap(CourseResource::getId, r -> r));
    }
}
