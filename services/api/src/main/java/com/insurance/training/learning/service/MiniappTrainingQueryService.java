package com.insurance.training.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
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
import com.insurance.training.content.storage.MinioStorageService;
import com.insurance.training.content.storage.ResourceKind;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.learning.vo.MiniappAssignmentVo;
import com.insurance.training.learning.vo.MiniappTaskDetailVo;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学员侧任务查询与任务上下文取资源地址。
 *
 * <p>资源版本由 {@code assignment.package_version_id → package_course.course_version_id} 推导，
 * **不使用课程的最新已发布版本**；否则旧任务会读到改版后的新内容。
 */
@Service
public class MiniappTrainingQueryService {

    private final TrainingAssignmentMapper assignmentMapper;
    private final TrainingTaskMapper taskMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final CourseMapper courseMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final CourseResourceMapper resourceMapper;
    private final LearningRecordMapper recordMapper;
    private final ResourceOpenLogMapper openLogMapper;
    private final LearnerGuard learnerGuard;
    private final LearningProgressService progressService;
    private final MinioStorageService storage;
    private final BusinessClock clock;
    private final int maxPageSize;

    public MiniappTrainingQueryService(TrainingAssignmentMapper assignmentMapper,
                                       TrainingTaskMapper taskMapper, PackageCourseMapper packageCourseMapper,
                                       CourseVersionMapper courseVersionMapper, CourseMapper courseMapper,
                                       CourseSectionMapper sectionMapper,
                                       CourseVersionResourceMapper bindingMapper,
                                       CourseResourceMapper resourceMapper, LearningRecordMapper recordMapper,
                                       ResourceOpenLogMapper openLogMapper,
                                       LearnerGuard learnerGuard, LearningProgressService progressService,
                                       MinioStorageService storage, BusinessClock clock,
                                       @Value("${app.page.max-size:200}") int maxPageSize) {
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.courseMapper = courseMapper;
        this.sectionMapper = sectionMapper;
        this.bindingMapper = bindingMapper;
        this.resourceMapper = resourceMapper;
        this.recordMapper = recordMapper;
        this.openLogMapper = openLogMapper;
        this.learnerGuard = learnerGuard;
        this.progressService = progressService;
        this.storage = storage;
        this.clock = clock;
        this.maxPageSize = maxPageSize;
    }

    /** 我的任务：未完成的排前面，再按截止时间升序。 */
    public PageResult<MiniappAssignmentVo> myTasks(CurrentUser user, Long page, Long pageSize) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<TrainingAssignment>lambdaQuery()
                .in(TrainingAssignment::getEmployeeId, learner.employeeIds())
                .orderByAsc(TrainingAssignment::getStatus)
                .orderByAsc(TrainingAssignment::getId);
        var result = assignmentMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pq.page(), pq.pageSize()),
                wrapper);
        // 批量加载任务，避免逐条 selectById（原实现分页 200 行时是 200 次查询）
        java.util.Set<Long> taskIds = result.getRecords().stream()
                .map(TrainingAssignment::getTaskId).collect(Collectors.toSet());
        Map<Long, TrainingTask> taskById = taskIds.isEmpty() ? Map.of()
                : taskMapper.selectList(Wrappers.<TrainingTask>lambdaQuery()
                        .in(TrainingTask::getId, taskIds)).stream()
                        .collect(Collectors.toMap(TrainingTask::getId, t -> t));
        List<MiniappAssignmentVo> items = result.getRecords().stream().map(a -> {
            TrainingTask task = taskById.get(a.getTaskId());
            LearningProgressService.Progress p = progressService.recalculate(a.getId());
            return MiniappAssignmentVo.of(a, task, p, clock.now());
        }).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    /** 任务详情：包内课程/章节 + 我的累计时长。 */
    public MiniappTaskDetailVo detail(CurrentUser user, long assignmentId) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = requireOwnAssignment(learner, assignmentId);
        TrainingTask task = taskMapper.selectById(assignment.getTaskId());
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训任务不存在");
        }

        List<CourseVersion> versions = versionsOf(assignment.getPackageVersionId());
        Map<Long, Course> courseById = loadCourses(versions);
        List<CourseSection> sections = sectionsOf(versions);
        List<CourseVersionResource> bindings = bindingsOf(versions);
        Map<Long, CourseResource> resourceById = loadResources(bindings);
        java.util.Set<Long> completedResources = recordMapper.selectList(Wrappers.<LearningRecord>lambdaQuery()
                        .eq(LearningRecord::getAssignmentId, assignmentId)
                        .eq(LearningRecord::getCompleted, 1))
                .stream().map(LearningRecord::getResourceId).collect(Collectors.toSet());
        java.util.Set<Long> openedResources = openLogMapper.selectList(Wrappers.<ResourceOpenLog>lambdaQuery()
                        .eq(ResourceOpenLog::getAssignmentId, assignmentId))
                .stream().map(ResourceOpenLog::getResourceId).collect(Collectors.toSet());

        Map<Long, List<CourseSection>> sectionsByVersion = sections.stream()
                .collect(Collectors.groupingBy(CourseSection::getCourseVersionId));
        Map<Long, List<CourseVersionResource>> bySection = bindings.stream()
                .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));

        List<MiniappTaskDetailVo.CourseItem> courses = new ArrayList<>();
        for (CourseVersion version : versions) {
            Course course = courseById.get(version.getCourseId());
            List<MiniappTaskDetailVo.SectionItem> items = new ArrayList<>();
            for (CourseSection section : sectionsByVersion.getOrDefault(version.getId(), List.of())) {
                List<MiniappTaskDetailVo.ResourceItem> resources = bySection
                        .getOrDefault(section.getId(), List.of()).stream()
                        .map(b -> resourceById.get(b.getResourceId()))
                        .filter(r -> r != null)
                        .map(r -> new MiniappTaskDetailVo.ResourceItem(r.getId(), r.getKind(), r.getOriginalName(),
                                r.getSizeBytes() == null ? 0 : r.getSizeBytes()))
                        .toList();
                List<CourseVersionResource> sectionBindings =
                        bySection.getOrDefault(section.getId(), List.of());
                boolean done = progressService.isSectionDone(sectionBindings, resourceById,
                        completedResources, openedResources);
                items.add(new MiniappTaskDetailVo.SectionItem(section.getId(), section.getTitle(),
                        section.getSortNo(), section.getDurationSeconds(), done, resources));
            }
            courses.add(new MiniappTaskDetailVo.CourseItem(version.getId(),
                    course == null ? null : course.getId(), version.getTitle(),
                    course == null ? null : course.getCoverResourceId(), items));
        }

        LearningProgressService.Progress p = progressService.recalculate(assignmentId);
        return new MiniappTaskDetailVo(assignment.getId(), task.getTitle(), task.getStartAt(),
                task.getLearnDeadlineAt(), p.completed() ? "COMPLETED" : assignment.getStatus(),
                p.courseTotal(), p.courseDone(), p.sectionTotal(), p.sectionDone(), courses);
    }

    /**
     * 任务上下文取资源签名地址。
     *
     * <p>资源必须属于该 assignment 的包版本；课程已 OFFLINE 也不影响已下发任务（快照仍可访问）。
     */
    public String resourceUrl(CurrentUser user, long assignmentId, long courseId, long resourceId) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = requireOwnAssignment(learner, assignmentId);
        TrainingTask task = taskMapper.selectById(assignment.getTaskId());
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训任务不存在");
        }
        if ("CLOSED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "任务已关闭，无法继续学习");
        }

        List<CourseVersion> versions = versionsOf(assignment.getPackageVersionId());
        CourseVersion version = versions.stream()
                .filter(v -> v.getCourseId() != null && v.getCourseId() == courseId)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "课程不属于当前培训任务"));
        CourseVersionResource binding = bindingMapper.selectOne(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getCourseVersionId, version.getId())
                .eq(CourseVersionResource::getResourceId, resourceId));
        if (binding == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不属于该课程版本");
        }
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        ResourceKind kind = ResourceKind.valueOf(resource.getKind());
        String downloadName = kind == ResourceKind.VIDEO ? null : resource.getOriginalName();
        return storage.presignedGetUrl(resource.getObjectKey(), kind, downloadName);
    }

    // ---------- 内部 ----------

    private TrainingAssignment requireOwnAssignment(LearnerContext learner, long assignmentId) {
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务分配不存在");
        }
        if (!learner.owns(assignment.getEmployeeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问他人的培训任务");
        }
        return assignment;
    }

    private List<CourseVersion> versionsOf(long packageVersionId) {
        List<Long> ids = packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .eq(PackageCourse::getPackageVersionId, packageVersionId)
                        .orderByAsc(PackageCourse::getSortNo))
                .stream().map(PackageCourse::getCourseVersionId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CourseVersion> byId = courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                        .in(CourseVersion::getId, ids))
                .stream().collect(Collectors.toMap(CourseVersion::getId, v -> v));
        // 保持包内排序
        return ids.stream().map(byId::get).filter(v -> v != null).toList();
    }

    private Map<Long, Course> loadCourses(List<CourseVersion> versions) {
        List<Long> ids = versions.stream().map(CourseVersion::getCourseId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courseMapper.selectList(Wrappers.<Course>lambdaQuery().in(Course::getId, ids))
                .stream().collect(Collectors.toMap(Course::getId, c -> c));
    }

    private List<CourseSection> sectionsOf(List<CourseVersion> versions) {
        List<Long> ids = versions.stream().map(CourseVersion::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                .in(CourseSection::getCourseVersionId, ids)
                .orderByAsc(CourseSection::getSortNo));
    }

    private List<CourseVersionResource> bindingsOf(List<CourseVersion> versions) {
        List<Long> ids = versions.stream().map(CourseVersion::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return bindingMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                .in(CourseVersionResource::getCourseVersionId, ids)
                .orderByAsc(CourseVersionResource::getSortNo));
    }

    private Map<Long, CourseResource> loadResources(List<CourseVersionResource> bindings) {
        List<Long> ids = bindings.stream().map(CourseVersionResource::getResourceId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery()
                        .in(CourseResource::getId, ids).eq(CourseResource::getStatus, "ACTIVE"))
                .stream().collect(Collectors.toMap(CourseResource::getId, r -> r));
    }
}