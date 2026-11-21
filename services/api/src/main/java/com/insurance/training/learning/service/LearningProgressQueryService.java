package com.insurance.training.learning.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
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
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.learning.vo.MiniappOverviewVo;
import com.insurance.training.learning.vo.MiniappProgressVo;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 学员进度查询。
 *
 * <p>与任务详情、心跳、open 共用 {@link LearningProgressService} 的完成判定，
 * 不复制算法：章节 {@code done} 只能有一个口径，否则两处会漂移。
 */
@Service
public class LearningProgressQueryService {

    private final LearnerGuard learnerGuard;
    private final LearningProgressService progressService;
    private final TrainingAssignmentMapper assignmentMapper;
    private final TrainingTaskMapper taskMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final CourseResourceMapper resourceMapper;
    private final LearningRecordMapper recordMapper;
    private final ResourceOpenLogMapper openLogMapper;

    public LearningProgressQueryService(LearnerGuard learnerGuard, LearningProgressService progressService,
                                        TrainingAssignmentMapper assignmentMapper, TrainingTaskMapper taskMapper,
                                        CourseVersionMapper courseVersionMapper, CourseSectionMapper sectionMapper,
                                        CourseVersionResourceMapper bindingMapper,
                                        CourseResourceMapper resourceMapper, LearningRecordMapper recordMapper,
                                        ResourceOpenLogMapper openLogMapper) {
        this.learnerGuard = learnerGuard;
        this.progressService = progressService;
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.sectionMapper = sectionMapper;
        this.bindingMapper = bindingMapper;
        this.resourceMapper = resourceMapper;
        this.recordMapper = recordMapper;
        this.openLogMapper = openLogMapper;
    }

    /** 单个任务的进度：任务级汇总 + 章节明细。 */
    public MiniappProgressVo byAssignment(CurrentUser user, long assignmentId) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务分配不存在");
        }
        if (!learner.owns(assignment.getEmployeeId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问他人的培训任务");
        }
        TrainingTask task = taskMapper.selectById(assignment.getTaskId());

        LearningProgressService.Progress progress = progressService.recalculate(assignmentId);

        List<Long> versionIds = progressService.courseVersionIds(assignment.getPackageVersionId());
        List<CourseVersion> versions = versionIds.isEmpty() ? List.of()
                : courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                        .in(CourseVersion::getId, versionIds));
        List<CourseSection> sections = versionIds.isEmpty() ? List.of()
                : sectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery()
                        .in(CourseSection::getCourseVersionId, versionIds)
                        .orderByAsc(CourseSection::getSortNo));
        List<CourseVersionResource> bindings = versionIds.isEmpty() ? List.of()
                : bindingMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                        .in(CourseVersionResource::getCourseVersionId, versionIds));
        Map<Long, CourseResource> resourceById = bindings.isEmpty() ? Map.of()
                : resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery()
                        .in(CourseResource::getId,
                                bindings.stream().map(CourseVersionResource::getResourceId).distinct().toList()))
                .stream().collect(Collectors.toMap(CourseResource::getId, r -> r));

        java.util.Set<Long> completedResources = recordMapper.selectList(Wrappers.<LearningRecord>lambdaQuery()
                        .eq(LearningRecord::getAssignmentId, assignmentId)
                        .eq(LearningRecord::getCompleted, 1))
                .stream().map(LearningRecord::getResourceId).collect(Collectors.toSet());
        java.util.Set<Long> opened = openLogMapper.selectList(Wrappers.<ResourceOpenLog>lambdaQuery()
                        .eq(ResourceOpenLog::getAssignmentId, assignmentId))
                .stream().map(ResourceOpenLog::getResourceId).collect(Collectors.toSet());

        Map<Long, List<CourseVersionResource>> bindingsBySection = bindings.stream()
                .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));

        List<MiniappProgressVo.SectionProgress> items = new ArrayList<>();
        for (CourseSection section : sections) {
            List<CourseVersionResource> sectionBindings =
                    bindingsBySection.getOrDefault(section.getId(), List.of());
            boolean done = progressService.isSectionDone(sectionBindings, resourceById,
                    completedResources, opened);
            items.add(new MiniappProgressVo.SectionProgress(section.getId(), section.getCourseVersionId(),
                    section.getTitle(), section.getDurationSeconds(), done));
        }

        boolean completed = progress.completed();
        return new MiniappProgressVo(assignment.getId(),
                task == null ? null : task.getTitle(),
                completed ? "COMPLETED" : assignment.getStatus(),
                task == null ? null : task.getLearnDeadlineAt(),
                progress.courseTotal(), progress.courseDone(),
                progress.sectionTotal(), progress.sectionDone(), completed, items);
    }

    /**
     * 学员总览：首页三个数字。
     *
     * <p>{@code pendingCourses} = 名下**未完成**任务里尚未完成的课程数；
     * {@code completedCourses} = 已完成课程的累计数（含已完成任务里的课程）；
     * 两者都来自服务端重算，不由前端累加。
     * 考试模块尚未实现，{@code pendingExams} 恒为 0 且 {@code examsAvailable=false}，
     * 这是明确的空实现而不是伪造数据。
     */
    public MiniappOverviewVo overview(CurrentUser user) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        List<TrainingAssignment> assignments = assignmentMapper.selectList(
                Wrappers.<TrainingAssignment>lambdaQuery()
                        .in(TrainingAssignment::getEmployeeId, learner.employeeIds()));
        long pendingCourses = 0;
        long completedCourses = 0;
        for (TrainingAssignment assignment : assignments) {
            LearningProgressService.Progress p = progressService.recalculate(assignment.getId());
            completedCourses += p.courseDone();
            if (!p.completed()) {
                pendingCourses += Math.max(0, p.courseTotal() - p.courseDone());
            }
        }
        return new MiniappOverviewVo(pendingCourses, completedCourses, 0, false);
    }
}
