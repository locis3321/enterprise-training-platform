package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本—章节—资源绑定。
 *
 * <p>绑定级路径：{@code /courses/{courseId}/versions/{versionNo}/sections/{sectionId}/resources/{resourceId}}，
 * 因为同一资源可被多个版本、多个章节引用。
 *
 * <p>换绑不提供原子接口：客户端先 {@code DELETE} 源绑定，再 {@code PUT} 目标绑定。
 * 单请求各自原子，不承诺跨请求事务。
 */
@Service
public class ResourceBindingService {

    private final CourseService courseService;
    private final CourseVersionService versionService;
    private final CourseVersionMapper versionMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final AuditService auditService;

    public ResourceBindingService(CourseService courseService, CourseVersionService versionService,
                                  CourseVersionMapper versionMapper, CourseSectionMapper sectionMapper,
                                  CourseResourceMapper resourceMapper,
                                  CourseVersionResourceMapper bindingMapper, AuditService auditService) {
        this.courseService = courseService;
        this.versionService = versionService;
        this.versionMapper = versionMapper;
        this.sectionMapper = sectionMapper;
        this.resourceMapper = resourceMapper;
        this.bindingMapper = bindingMapper;
        this.auditService = auditService;
    }

    /** 绑定：仅 DRAFT 版本；章节属于该版本；资源属于该课程且 ACTIVE；重复绑定幂等。 */
    @Transactional
    public void bind(CurrentUser user, long courseId, int versionNo, long sectionId, long resourceId, String ip) {
        courseService.getWritable(user, courseId);
        CourseVersion version = versionService.getWritableDraft(user, courseId, versionNo);
        requireSection(version, sectionId);
        requireResource(courseId, resourceId);

        Long existing = bindingMapper.selectCount(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getCourseVersionId, version.getId())
                .eq(CourseVersionResource::getSectionId, sectionId)
                .eq(CourseVersionResource::getResourceId, resourceId));
        if (existing != null && existing > 0) {
            return; // 幂等
        }

        int inserted;
        try {
            // 条件写入：'版本仍为 DRAFT' 与插入在同一语句，避免并发发布后写进冻结快照
            inserted = bindingMapper.insertIfDraft(version.getId(), sectionId, resourceId,
                    nextSortNo(version.getId(), sectionId));
        }
        catch (DuplicateKeyException ex) {
            // 并发下另一请求已插入同一绑定，视为幂等成功
            return;
        }
        if (inserted == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，资源未绑定");
        }
        auditService.record(user, "RESOURCE_BOUND", "course_version", version.getId(),
                "courseId=" + courseId + ",versionNo=" + versionNo + ",sectionId=" + sectionId
                        + ",resourceId=" + resourceId, ip);
    }

    /** 解绑：仅 DRAFT 版本；不存在该绑定返回 404。不删除对象（可能被其他版本引用）。 */
    @Transactional
    public void unbind(CurrentUser user, long courseId, int versionNo, long sectionId, long resourceId, String ip) {
        courseService.getWritable(user, courseId);
        CourseVersion version = versionService.getWritableDraft(user, courseId, versionNo);

        CourseVersionResource binding = bindingMapper.selectOne(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getCourseVersionId, version.getId())
                .eq(CourseVersionResource::getSectionId, sectionId)
                .eq(CourseVersionResource::getResourceId, resourceId));
        if (binding == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该资源未绑定到指定章节");
        }
        int deleted = bindingMapper.deleteIfDraft(version.getId(), sectionId, resourceId);
        if (deleted == 0) {
            CourseVersion current = versionMapper.selectById(version.getId());
            if (current == null || !"DRAFT".equals(current.getStatus())) {
                throw new BizException(ErrorCode.CONFLICT, "该版本已被发布，解绑未生效");
            }
            throw new BizException(ErrorCode.NOT_FOUND, "该资源未绑定到指定章节");
        }
        auditService.record(user, "RESOURCE_UNBOUND", "course_version_resource", binding.getId(),
                "courseId=" + courseId + ",versionNo=" + versionNo + ",sectionId=" + sectionId, ip);
    }

    private void requireSection(CourseVersion version, long sectionId) {
        CourseSection section = sectionMapper.selectById(sectionId);
        if (section == null || !version.getId().equals(section.getCourseVersionId())) {
            // 跨版本拼接章节 ID 一律视为不存在，避免探测其他版本结构
            throw new BizException(ErrorCode.NOT_FOUND, "章节不存在或不属于该版本");
        }
    }

    private void requireResource(long courseId, long resourceId) {
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())
                || !resource.getCourseId().equals(courseId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在或不属于该课程");
        }
    }

    private int nextSortNo(long versionId, long sectionId) {
        Long count = bindingMapper.selectCount(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getCourseVersionId, versionId)
                .eq(CourseVersionResource::getSectionId, sectionId));
        return (count == null ? 0 : count.intValue()) + 1;
    }
}
