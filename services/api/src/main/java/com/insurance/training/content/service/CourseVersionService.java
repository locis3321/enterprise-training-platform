package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.dto.CourseVersionRequest;
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
import com.insurance.training.content.vo.CourseVersionVo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 版本生命周期：新建、修改、发布、复制。 */
@Service
public class CourseVersionService {

    private final CourseService courseService;
    private final CourseMapper courseMapper;
    private final CourseVersionMapper versionMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final AuditService auditService;

    public CourseVersionService(CourseService courseService, CourseMapper courseMapper,
                                CourseVersionMapper versionMapper, CourseSectionMapper sectionMapper,
                                CourseResourceMapper resourceMapper,
                                CourseVersionResourceMapper bindingMapper, AuditService auditService) {
        this.courseService = courseService;
        this.courseMapper = courseMapper;
        this.versionMapper = versionMapper;
        this.sectionMapper = sectionMapper;
        this.resourceMapper = resourceMapper;
        this.bindingMapper = bindingMapper;
        this.auditService = auditService;
    }

    public List<CourseVersionVo> list(CurrentUser user, long courseId) {
        courseService.getVisible(user, courseId);
        List<CourseVersion> versions = versionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .orderByDesc(CourseVersion::getVersionNo));
        if (versions.isEmpty()) {
            return List.of();
        }
        List<Long> versionIds = versions.stream().map(CourseVersion::getId).toList();
        Map<Long, Long> sectionCounts = sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                        .in(CourseSection::getCourseVersionId, versionIds))
                .stream().collect(Collectors.groupingBy(CourseSection::getCourseVersionId, Collectors.counting()));
        Map<Long, Long> bindingCounts = bindingMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                        .in(CourseVersionResource::getCourseVersionId, versionIds))
                .stream().collect(Collectors.groupingBy(CourseVersionResource::getCourseVersionId, Collectors.counting()));

        return versions.stream()
                .map(v -> CourseVersionVo.of(v,
                        sectionCounts.getOrDefault(v.getId(), 0L),
                        bindingCounts.getOrDefault(v.getId(), 0L)))
                .toList();
    }

    /** 读取版本并校验其属于该课程；版本号不存在返回 404（不回落最新）。 */
    public CourseVersion getOwnedVersion(long courseId, int versionNo) {
        CourseVersion version = versionMapper.selectOne(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .eq(CourseVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "版本不存在");
        }
        return version;
    }

    /** 可写校验 + 版本必须为 DRAFT（已发布版本不可修改）。 */
    public CourseVersion getWritableDraft(CurrentUser user, long courseId, int versionNo) {
        courseService.getWritable(user, courseId);
        CourseVersion version = getOwnedVersion(courseId, versionNo);
        if (!"DRAFT".equals(version.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "已发布版本不可修改，请复制为新版本后再改");
        }
        return version;
    }

    /** 新建草稿版本：version_no 原子递增。 */
    @Transactional
    public CourseVersionVo create(CurrentUser user, long courseId, CourseVersionRequest request, String ip) {
        Course course = courseService.getWritable(user, courseId);
        int nextNo = bumpLatestVersion(course);

        CourseVersion version = new CourseVersion();
        version.setCourseId(courseId);
        version.setVersionNo(nextNo);
        version.setTitle(request != null && request.title() != null && !request.title().isBlank()
                ? request.title().trim() : course.getTitle());
        version.setDescription(request != null && request.description() != null
                ? request.description() : course.getDescription());
        version.setStatus("DRAFT");
        try {
            versionMapper.insert(version);
        }
        catch (DuplicateKeyException ex) {
            // 并发：另一请求已用掉该版本号
            throw new BizException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }
        auditService.record(user, "VERSION_CREATED", "course_version", version.getId(),
                "courseId=" + courseId + ",versionNo=" + nextNo, ip);
        return CourseVersionVo.of(version, 0, 0);
    }

    /** 修改版本标题/简介：仅 DRAFT。 */
    @Transactional
    public CourseVersionVo update(CurrentUser user, long courseId, int versionNo,
                                  CourseVersionRequest request, String ip) {
        CourseVersion version = getWritableDraft(user, courseId, versionNo);
        String newTitle = request.title() == null || request.title().isBlank()
                ? version.getTitle() : request.title().trim();
        String newDescription = request.description() == null
                ? version.getDescription() : request.description();
        // 条件更新：仅在仍为 DRAFT 时生效，避免与并发发布竞争时覆盖已发布快照
        int updated = versionMapper.update(null, Wrappers.<CourseVersion>lambdaUpdate()
                .set(CourseVersion::getTitle, newTitle)
                .set(CourseVersion::getDescription, newDescription)
                .eq(CourseVersion::getId, version.getId())
                .eq(CourseVersion::getStatus, "DRAFT"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，修改未生效");
        }
        version.setTitle(newTitle);
        version.setDescription(newDescription);
        auditService.record(user, "VERSION_UPDATED", "course_version", version.getId(),
                "versionNo=" + versionNo, ip);
        return CourseVersionVo.of(version, 0, 0);
    }

    /**
     * 发布版本（同事务）。
     *
     * <p>校验：DRAFT、至少 1 个章节、每章节至少 1 条绑定且资源 ACTIVE。
     * 更新：版本 PUBLISHED、课程 PUBLISHED 且 published_version_no 指向该版本。
     * 已发布版本或旧版本一律 409；回滚只能走 copy → 改 → publish。
     */
    @Transactional
    public CourseVersionVo publish(CurrentUser user, long courseId, int versionNo, String ip) {
        Course course = courseService.getWritable(user, courseId);
        CourseVersion version = getOwnedVersion(courseId, versionNo);
        if (!"DRAFT".equals(version.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已发布，不能重复发布");
        }
        if (course.getPublishedVersionNo() != null && versionNo < course.getPublishedVersionNo()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "不能发布早于当前生效版本的历史版本；如需回滚请复制该版本后重新发布");
        }
        assertPublishable(version);

        // 版本状态 CAS：并发发布同一版本时只有一个成功
        int versionUpdated = versionMapper.update(null, Wrappers.<CourseVersion>lambdaUpdate()
                .set(CourseVersion::getStatus, "PUBLISHED")
                .set(CourseVersion::getPublishedAt, LocalDateTime.now())
                .set(CourseVersion::getPublishedByAdminId, user.id())
                .eq(CourseVersion::getId, version.getId())
                .eq(CourseVersion::getStatus, "DRAFT"));
        if (versionUpdated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被其他操作发布");
        }

        // 课程指针 CAS：防止并发发布两个版本时指针回退
        int courseUpdated = courseMapper.update(null, Wrappers.<Course>lambdaUpdate()
                .set(Course::getStatus, "PUBLISHED")
                .set(Course::getPublishedVersionNo, versionNo)
                .eq(Course::getId, course.getId())
                .and(w -> w.isNull(Course::getPublishedVersionNo)
                        .or().le(Course::getPublishedVersionNo, versionNo)));
        if (courseUpdated == 0) {
            // 课程指针已被更新到更高版本：回滚版本状态，保持一致性
            throw new BizException(ErrorCode.CONFLICT, "课程生效版本已被更新，请刷新后重试");
        }
        version.setStatus("PUBLISHED");
        course.setStatus("PUBLISHED");
        course.setPublishedVersionNo(versionNo);

        auditService.record(user, "VERSION_PUBLISHED", "course_version", version.getId(),
                "courseId=" + courseId + ",versionNo=" + versionNo, ip);
        return CourseVersionVo.of(version, countSections(version.getId()), countBindings(version.getId()));
    }

    /**
     * 复制版本为新草稿。
     *
     * <p>深复制章节（新 ID）与绑定行（指向同一 resource_id）；不复制资源元数据与对象。
     */
    @Transactional
    public CourseVersionVo copy(CurrentUser user, long courseId, int versionNo, String ip) {
        Course course = courseService.getWritable(user, courseId);
        CourseVersion source = getOwnedVersion(courseId, versionNo);
        int nextNo = bumpLatestVersion(course);

        CourseVersion target = new CourseVersion();
        target.setCourseId(courseId);
        target.setVersionNo(nextNo);
        target.setTitle(source.getTitle());
        target.setDescription(source.getDescription());
        target.setStatus("DRAFT");
        try {
            versionMapper.insert(target);
        }
        catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }

        List<CourseSection> sections = sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, source.getId())
                .orderByAsc(CourseSection::getSortNo));
        Map<Long, Long> sectionIdMap = new java.util.HashMap<>();
        for (CourseSection s : sections) {
            CourseSection copy = new CourseSection();
            copy.setCourseVersionId(target.getId());
            copy.setTitle(s.getTitle());
            copy.setSortNo(s.getSortNo());
            copy.setDurationSeconds(s.getDurationSeconds());
            sectionMapper.insert(copy);
            sectionIdMap.put(s.getId(), copy.getId());
        }

        List<CourseVersionResource> bindings = bindingMapper.selectList(
                Wrappers.<CourseVersionResource>lambdaQuery()
                        .eq(CourseVersionResource::getCourseVersionId, source.getId()));
        for (CourseVersionResource b : bindings) {
            Long newSectionId = sectionIdMap.get(b.getSectionId());
            if (newSectionId == null) {
                continue;
            }
            CourseVersionResource copy = new CourseVersionResource();
            copy.setCourseVersionId(target.getId());
            copy.setSectionId(newSectionId);
            copy.setResourceId(b.getResourceId());
            copy.setSortNo(b.getSortNo());
            bindingMapper.insert(copy);
        }

        auditService.record(user, "VERSION_COPIED", "course_version", target.getId(),
                "from=" + versionNo + ",to=" + nextNo, ip);
        return CourseVersionVo.of(target, sections.size(), bindings.size());
    }

    /** 发布完整性：至少 1 章节；每章节至少 1 条绑定且资源 ACTIVE。 */
    private void assertPublishable(CourseVersion version) {
        List<CourseSection> sections = sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, version.getId()));
        if (sections.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "版本没有任何章节，不能发布");
        }
        List<CourseVersionResource> bindings = bindingMapper.selectList(
                Wrappers.<CourseVersionResource>lambdaQuery()
                        .eq(CourseVersionResource::getCourseVersionId, version.getId()));
        if (bindings.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "版本没有绑定任何资源，不能发布");
        }
        Map<Long, Long> perSection = bindings.stream()
                .collect(Collectors.groupingBy(CourseVersionResource::getSectionId, Collectors.counting()));
        for (CourseSection s : sections) {
            if (perSection.getOrDefault(s.getId(), 0L) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "章节「" + s.getTitle() + "」没有资源，不能发布");
            }
        }
        List<Long> resourceIds = bindings.stream().map(CourseVersionResource::getResourceId).distinct().toList();
        List<CourseResource> resources = resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery()
                .in(CourseResource::getId, resourceIds)
                .eq(CourseResource::getStatus, "ACTIVE"));
        if (resources.size() != resourceIds.size()) {
            throw new BizException(ErrorCode.CONFLICT, "存在已删除的资源，不能发布");
        }
    }

    private long countSections(long versionId) {
        return sectionMapper.selectCount(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, versionId));
    }

    private long countBindings(long versionId) {
        return bindingMapper.selectCount(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getCourseVersionId, versionId));
    }

    /**
     * 原子递增 latest_version_no：条件更新，影响行数为 0 说明并发冲突。
     *
     * @return 新版本号
     */
    private int bumpLatestVersion(Course course) {
        Integer current = course.getLatestVersionNo();
        if (current == null) {
            // 建课时一定会初始化；缺失说明数据异常，显式失败而不是猜一个值
            throw new BizException(ErrorCode.INTERNAL_ERROR, "课程版本号缺失，数据异常");
        }
        int next = current + 1;
        int updated = courseMapper.update(null, Wrappers.<Course>lambdaUpdate()
                .set(Course::getLatestVersionNo, next)
                .eq(Course::getId, course.getId())
                .eq(Course::getLatestVersionNo, current));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "版本号并发冲突，请重试");
        }
        course.setLatestVersionNo(next);
        return next;
    }
}
