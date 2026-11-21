package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.dto.SectionRequest;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 章节编排。所有写操作要求版本为 DRAFT，且章节必须属于该版本。
 */
@Service
public class CourseSectionService {

    private final CourseService courseService;
    private final CourseVersionService versionService;
    private final CourseSectionMapper sectionMapper;
    private final CourseVersionMapper versionMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final AuditService auditService;

    public CourseSectionService(CourseService courseService, CourseVersionService versionService,
                                CourseSectionMapper sectionMapper, CourseVersionMapper versionMapper,
                                CourseVersionResourceMapper bindingMapper, AuditService auditService) {
        this.courseService = courseService;
        this.versionService = versionService;
        this.sectionMapper = sectionMapper;
        this.versionMapper = versionMapper;
        this.bindingMapper = bindingMapper;
        this.auditService = auditService;
    }

    public List<CourseSection> list(CurrentUser user, long courseId, int versionNo) {
        courseService.getVisible(user, courseId);
        CourseVersion version = versionService.getOwnedVersion(courseId, versionNo);
        return sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, version.getId())
                .orderByAsc(CourseSection::getSortNo));
    }

    @Transactional
    public CourseSection create(CurrentUser user, long courseId, int versionNo,
                                SectionRequest request, String ip) {
        CourseVersion version = versionService.getWritableDraft(user, courseId, versionNo);
        int sortNo = request.sortNo() != null ? request.sortNo() : nextSortNo(version.getId());
        String title = request.title().trim();

        int inserted;
        try {
            // 条件写入：'版本仍为 DRAFT' 与插入在同一语句，避免并发发布后写进冻结快照
            inserted = sectionMapper.insertIfDraft(version.getId(), title, sortNo, request.durationSeconds());
        }
        catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.CONFLICT, "该排序号已被占用，请换一个");
        }
        if (inserted == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，章节未创建");
        }
        // 条件插入不返回自增 id，按 (version, sortNo) 唯一键回查；前端后续绑资源需要章节 id
        CourseSection section = sectionMapper.selectOne(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, version.getId())
                .eq(CourseSection::getSortNo, sortNo));
        if (section == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "章节创建后回查失败，数据异常");
        }
        auditService.record(user, "SECTION_CREATED", "course_version", version.getId(),
                "courseId=" + courseId + ",versionNo=" + versionNo + ",sortNo=" + sortNo, ip);
        return section;
    }

    @Transactional
    public CourseSection update(CurrentUser user, long courseId, int versionNo, long sectionId,
                                SectionRequest request, String ip) {
        CourseVersion version = versionService.getWritableDraft(user, courseId, versionNo);
        CourseSection section = requireOwnedSection(version, sectionId);

        String title = request.title().trim();
        int sortNo = request.sortNo() != null ? request.sortNo() : section.getSortNo();
        Integer durationSeconds = request.durationSeconds() != null
                ? request.durationSeconds() : section.getDurationSeconds();

        int updated;
        try {
            updated = sectionMapper.updateIfDraft(sectionId, version.getId(), title, sortNo, durationSeconds);
        }
        catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.CONFLICT, "该排序号已被占用，请换一个");
        }
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，修改未生效");
        }
        section.setTitle(title);
        section.setSortNo(sortNo);
        section.setDurationSeconds(durationSeconds);
        auditService.record(user, "SECTION_UPDATED", "course_section", sectionId, null, ip);
        return section;
    }

    /**
     * 删除章节。
     *
     * <p>若有资源绑定则 409（要求先解绑），不级联删除绑定——避免静默丢失编排关系。
     * 删除同样走条件语句，被并发发布时 409 而不是删掉已冻结快照的章节。
     */
    @Transactional
    public void delete(CurrentUser user, long courseId, int versionNo, long sectionId, String ip) {
        CourseVersion version = versionService.getWritableDraft(user, courseId, versionNo);
        CourseSection section = requireOwnedSection(version, sectionId);

        Long bindings = bindingMapper.selectCount(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getSectionId, sectionId));
        if (bindings != null && bindings > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "章节下还有 " + bindings + " 个资源，请先解绑后再删除章节");
        }
        if (sectionMapper.deleteIfDraft(sectionId, version.getId()) == 0) {
            throw publishedOrMissing(version.getId());
        }
        auditService.record(user, "SECTION_DELETED", "course_section", sectionId, null, ip);
    }

    /** 条件写 0 行时区分「已被并发发布」（409）与「章节已不存在」（404）。 */
    private BizException publishedOrMissing(long versionId) {
        CourseVersion current = versionMapper.selectById(versionId);
        if (current == null || !"DRAFT".equals(current.getStatus())) {
            return new BizException(ErrorCode.CONFLICT, "该版本已被发布，操作未生效");
        }
        return new BizException(ErrorCode.NOT_FOUND, "章节不存在");
    }

    private CourseSection requireOwnedSection(CourseVersion version, long sectionId) {
        CourseSection section = sectionMapper.selectById(sectionId);
        if (section == null || !version.getId().equals(section.getCourseVersionId())) {
            // 跨版本拼接章节 ID 一律 404，避免探测其他版本结构
            throw new BizException(ErrorCode.NOT_FOUND, "章节不存在或不属于该版本");
        }
        return section;
    }

    private int nextSortNo(long versionId) {
        CourseSection last = sectionMapper.selectOne(Wrappers.<CourseSection>lambdaQuery()
                .eq(CourseSection::getCourseVersionId, versionId)
                .orderByDesc(CourseSection::getSortNo)
                .last("limit 1"));
        return last == null || last.getSortNo() == null ? 1 : last.getSortNo() + 1;
    }
}
