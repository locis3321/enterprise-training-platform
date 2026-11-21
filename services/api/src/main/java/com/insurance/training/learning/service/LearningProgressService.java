package com.insurance.training.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
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
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学习进度与完成判定。
 *
 * <p>只用服务端数据：章节时长取 {@code course_section.duration_seconds}，
 * 视频时长取 {@code learning_record.effective_seconds} 之和，资料取 {@code resource_open_log}。
 * 不读取任何客户端上报的百分比。
 */
@Service
public class LearningProgressService {

    private final TrainingAssignmentMapper assignmentMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final CourseMapper courseMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final CourseResourceMapper resourceMapper;
    private final LearningRecordMapper recordMapper;
    private final ResourceOpenLogMapper openLogMapper;

    public LearningProgressService(TrainingAssignmentMapper assignmentMapper,
                                   PackageCourseMapper packageCourseMapper,
                                   CourseVersionMapper courseVersionMapper, CourseMapper courseMapper,
                                   CourseSectionMapper sectionMapper, CourseVersionResourceMapper bindingMapper,
                                   CourseResourceMapper resourceMapper, LearningRecordMapper recordMapper,
                                   ResourceOpenLogMapper openLogMapper) {
        this.assignmentMapper = assignmentMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.courseMapper = courseMapper;
        this.sectionMapper = sectionMapper;
        this.bindingMapper = bindingMapper;
        this.resourceMapper = resourceMapper;
        this.recordMapper = recordMapper;
        this.openLogMapper = openLogMapper;
    }

    /** 本次任务在包版本里的课程版本 id 列表。 */
    public List<Long> courseVersionIds(long packageVersionId) {
        return packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .eq(PackageCourse::getPackageVersionId, packageVersionId)
                        .orderByAsc(PackageCourse::getSortNo))
                .stream().map(PackageCourse::getCourseVersionId).toList();
    }

    /** 先按当前数据算一遍并落状态；返回最终进度。 */
    public Progress recalculate(long assignmentId) {
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务分配不存在");
        }
        if ("COMPLETED".equals(assignment.getStatus())) {
            // 只进不退：已完成的任务不再重算，避免数据回退引起争议
            List<CourseVersion> versions = courseVersions(assignment.getPackageVersionId());
            Progress snapshot = compute(assignment, versions);
            return new Progress(snapshot.courseTotal(), snapshot.courseDone(),
                    snapshot.sectionTotal(), snapshot.sectionDone(), true);
        }
        Progress progress = compute(assignment, courseVersions(assignment.getPackageVersionId()));
        if (progress.completed()) {
            assignmentMapper.completeIfNotCompleted(assignmentId, LocalDateTime.now());
        }
        return progress;
    }

    private List<CourseVersion> courseVersions(long packageVersionId) {
        List<Long> ids = courseVersionIds(packageVersionId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                .in(CourseVersion::getId, ids));
    }

    private Progress compute(TrainingAssignment assignment, List<CourseVersion> versions) {
        if (versions.isEmpty()) {
            return new Progress(0, 0, 0, 0, false);
        }
        List<Long> versionIds = versions.stream().map(CourseVersion::getId).toList();
        List<CourseSection> sections = sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .in(CourseSection::getCourseVersionId, versionIds)
                .orderByAsc(CourseSection::getSortNo));
        List<CourseVersionResource> bindings = bindingMapper.selectList(
                Wrappers.<CourseVersionResource>lambdaQuery()
                        .in(CourseVersionResource::getCourseVersionId, versionIds));
        Map<Long, CourseResource> resources = loadResources(bindings);
        java.util.Set<Long> completedResources = completedVideoResources(assignment.getId());
        java.util.Set<Long> openedResources = openedResources(assignment.getId());

        Map<Long, List<CourseSection>> sectionsByVersion = sections.stream()
                .collect(Collectors.groupingBy(CourseSection::getCourseVersionId));
        Map<Long, List<CourseVersionResource>> bindingsBySection = bindings.stream()
                .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));

        int sectionDone = 0;
        Map<Long, Boolean> sectionCompletion = new java.util.HashMap<>();
        for (CourseSection section : sections) {
            boolean done = isSectionDone(bindingsBySection.getOrDefault(section.getId(), List.of()),
                    resources, completedResources, openedResources);
            sectionCompletion.put(section.getId(), done);
            if (done) {
                sectionDone++;
            }
        }

        int courseDone = 0;
        for (CourseVersion version : versions) {
            List<CourseSection> own = sectionsByVersion.getOrDefault(version.getId(), List.of());
            // 没有任何章节的课程版本视为未完成：不把空壳算成已完成
            if (!own.isEmpty() && own.stream().allMatch(s -> sectionCompletion.getOrDefault(s.getId(), false))) {
                courseDone++;
            }
        }
        return new Progress(versions.size(), courseDone, sections.size(), sectionDone, courseDone == versions.size());
    }

    /**
     * 章节完成判定：供任务详情、progress 接口、课程页共用同一套口径。
     *
     * <p>视频章节完成 = 该章节**全部 VIDEO 资源均已播完**（{@code learning_record.completed}）；
     * 纯资料章节完成 = 全部资源已打开（{@code resource_open_log}）。
     *
     * <p>为什么不需要时长：播放器已禁用拖动，「播完」是客户端能给出的、
     * 且无法绕过（不播完就不会触发 ended）的完成信号。因此不再按时长折算。
     *
     * <p>只读视角：数据异常（无资源）记为「未完成」，不让一个坏章节拖垮整体统计。
     */
    public boolean isSectionDone(List<CourseVersionResource> bindings,
                                 Map<Long, CourseResource> resources,
                                 java.util.Set<Long> completedResources,
                                 java.util.Set<Long> openedResources) {
        if (bindings.isEmpty()) {
            return false;
        }
        List<CourseVersionResource> videoBindings = bindings.stream()
                .filter(b -> isVideo(resources.get(b.getResourceId())))
                .toList();
        if (!videoBindings.isEmpty()) {
            // 章节内每个视频都播完才算完成；混合章节里的资料不阻塞完成
            return videoBindings.stream()
                    .allMatch(b -> completedResources.contains(b.getResourceId()));
        }
        return bindings.stream().allMatch(b -> openedResources.contains(b.getResourceId()));
    }

    private boolean isVideo(CourseResource resource) {
        return resource != null && "VIDEO".equals(resource.getKind());
    }

    /** 该 assignment 下已播完的视频资源集合。 */
    private java.util.Set<Long> completedVideoResources(long assignmentId) {
        return recordMapper.selectList(Wrappers.<LearningRecord>lambdaQuery()
                        .eq(LearningRecord::getAssignmentId, assignmentId)
                        .eq(LearningRecord::getCompleted, 1))
                .stream().map(LearningRecord::getResourceId).collect(Collectors.toSet());
    }

    private java.util.Set<Long> openedResources(long assignmentId) {
        return openLogMapper.selectList(Wrappers.<ResourceOpenLog>lambdaQuery()
                        .eq(ResourceOpenLog::getAssignmentId, assignmentId))
                .stream().map(ResourceOpenLog::getResourceId).collect(Collectors.toSet());
    }

    private Map<Long, CourseResource> loadResources(List<CourseVersionResource> bindings) {
        if (bindings.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = bindings.stream().map(CourseVersionResource::getResourceId).distinct().toList();
        return resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery().in(CourseResource::getId, ids))
                .stream().collect(Collectors.toMap(CourseResource::getId, r -> r));
    }

    /** 进度快照。{@code completed} 表示整个任务是否完成。 */
    public record Progress(long courseTotal, long courseDone, long sectionTotal, long sectionDone,
                           boolean completed) {
    }
}