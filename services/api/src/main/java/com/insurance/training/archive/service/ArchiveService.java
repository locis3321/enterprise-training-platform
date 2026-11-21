package com.insurance.training.archive.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.archive.vo.ExamRecordVo;
import com.insurance.training.archive.vo.LearningArchiveVo;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.exam.entity.ExamAttempt;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.exam.service.ExamAttemptService;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.learning.service.LearningProgressService;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 学员档案（只读）。
 *
 * <p>与「考试」页（只列待考）、「成绩」页（单任务最佳）互补：这里给出**累计汇总**与
 * **全部历史流水**，因此「培训档案」与「考试记录」合并为同一页面的两个 tab，
 * 不再各建一套考试历史。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li>只读持久化事实，不调用 {@code LearningProgressService.recalculate}——后者会写
 *       {@code completeIfNotCompleted}，在列表场景会造成 N 次写与 N+1 查询。</li>
 *   <li>章节完成判定复用 {@link LearningProgressService#isSectionDone}，不另写一套规则。</li>
 * </ul>
 */
@Service
public class ArchiveService {

    private static final int MAX_EXAM_PAGE_SIZE = 100;

    private final LearnerGuard learnerGuard;
    private final LearningProgressService progressService;
    private final TrainingAssignmentMapper assignmentMapper;
    private final TrainingTaskMapper taskMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseVersionResourceMapper bindingMapper;
    private final CourseResourceMapper resourceMapper;
    private final LearningRecordMapper recordMapper;
    private final ResourceOpenLogMapper openLogMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamPaperMapper paperMapper;
    private final EnterpriseMapper enterpriseMapper;

    public ArchiveService(LearnerGuard learnerGuard, LearningProgressService progressService,
                          TrainingAssignmentMapper assignmentMapper, TrainingTaskMapper taskMapper,
                          PackageCourseMapper packageCourseMapper, CourseVersionResourceMapper bindingMapper,
                          CourseResourceMapper resourceMapper,
                          LearningRecordMapper recordMapper, ResourceOpenLogMapper openLogMapper,
                          ExamAttemptMapper attemptMapper, ExamPaperMapper paperMapper,
                          EnterpriseMapper enterpriseMapper) {
        this.learnerGuard = learnerGuard;
        this.progressService = progressService;
        this.assignmentMapper = assignmentMapper;
        this.taskMapper = taskMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.bindingMapper = bindingMapper;
        this.resourceMapper = resourceMapper;
        this.recordMapper = recordMapper;
        this.openLogMapper = openLogMapper;
        this.attemptMapper = attemptMapper;
        this.paperMapper = paperMapper;
        this.enterpriseMapper = enterpriseMapper;
    }

    /** 学习档案：累计汇总 + 全部任务归档（按 id 倒序，新任务在前）。 */
    public LearningArchiveVo learning(CurrentUser user) {
        LearnerContext learner = learnerGuard.requireLearner(user);
        List<TrainingAssignment> assignments = ownAssignments(learner);
        if (assignments.isEmpty()) {
            return new LearningArchiveVo(
                    new LearningArchiveVo.Summary(0, 0, 0, 0, 0d, 0), List.of());
        }

        List<Long> assignmentIds = assignments.stream().map(TrainingAssignment::getId).toList();
        Map<Long, TrainingTask> tasks = taskMap(assignments);
        Map<Long, String> enterpriseNames = enterpriseNames(learner);

        // 结构：包版本 → 课程版本 → 章节 → 资源绑定，全部批量取
        Set<Long> packageVersionIds = assignments.stream()
                .map(TrainingAssignment::getPackageVersionId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<Long>> coursesByPackage = packageCourseIds(packageVersionIds);
        Set<Long> courseVersionIds = coursesByPackage.values().stream()
                .flatMap(List::stream).collect(Collectors.toSet());
        Map<Long, List<CourseVersionResource>> bindingsByCourse = bindingsByCourse(courseVersionIds);
        Map<Long, CourseResource> resources = resourceMap(bindingsByCourse);

        // 事实：学习记录与资料打开登记，按 assignment 分组
        Map<Long, Set<Long>> completedByAssignment = new HashMap<>();
        Map<Long, Integer> secondsByAssignment = new HashMap<>();
        for (LearningRecord r : recordMapper.selectList(Wrappers.<LearningRecord>lambdaQuery()
                .in(LearningRecord::getAssignmentId, assignmentIds))) {
            if (Integer.valueOf(1).equals(r.getCompleted()) && r.getResourceId() != null) {
                completedByAssignment.computeIfAbsent(r.getAssignmentId(), k -> new HashSet<>())
                        .add(r.getResourceId());
            }
            secondsByAssignment.merge(r.getAssignmentId(),
                    r.getEffectiveSeconds() == null ? 0 : r.getEffectiveSeconds(), Integer::sum);
        }
        Map<Long, Set<Long>> openedByAssignment = new HashMap<>();
        for (ResourceOpenLog log : openLogMapper.selectList(Wrappers.<ResourceOpenLog>lambdaQuery()
                .in(ResourceOpenLog::getAssignmentId, assignmentIds))) {
            openedByAssignment.computeIfAbsent(log.getAssignmentId(), k -> new HashSet<>())
                    .add(log.getResourceId());
        }

        Map<Long, List<ExamAttempt>> attemptsByAssignment = submittedAttempts(assignmentIds);

        List<LearningArchiveVo.TaskRow> rows = new ArrayList<>(assignments.size());
        int taskCompleted = 0;
        int courseTotal = 0;
        int courseCompleted = 0;
        int totalSeconds = 0;
        int examPassed = 0;

        for (TrainingAssignment a : assignments) {
            TrainingTask task = tasks.get(a.getTaskId());
            List<Long> cvIds = coursesByPackage.getOrDefault(a.getPackageVersionId(), List.of());
            Set<Long> completed = completedByAssignment.getOrDefault(a.getId(), Set.of());
            Set<Long> opened = openedByAssignment.getOrDefault(a.getId(), Set.of());

            int sectionsDone = 0;
            int sectionsAll = 0;
            int coursesDone = 0;
            for (Long cvId : cvIds) {
                Map<Long, List<CourseVersionResource>> bySection = bindingsByCourse
                        .getOrDefault(cvId, List.of()).stream()
                        .filter(b -> b.getSectionId() != null)
                        .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));
                boolean courseDone = !bySection.isEmpty();
                for (List<CourseVersionResource> binds : bySection.values()) {
                    sectionsAll++;
                    boolean done = progressService.isSectionDone(binds, resources, completed, opened);
                    if (done) {
                        sectionsDone++;
                    }
                    else {
                        courseDone = false;
                    }
                }
                if (courseDone) {
                    coursesDone++;
                }
            }

            int seconds = secondsByAssignment.getOrDefault(a.getId(), 0);
            boolean isCompleted = "COMPLETED".equals(a.getStatus());
            ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(
                    attemptsByAssignment.getOrDefault(a.getId(), List.of()));

            taskCompleted += isCompleted ? 1 : 0;
            courseTotal += cvIds.size();
            courseCompleted += coursesDone;
            totalSeconds += seconds;
            examPassed += (best != null && best.passed()) ? 1 : 0;

            rows.add(new LearningArchiveVo.TaskRow(
                    a.getId(), a.getTaskId(),
                    task == null ? "培训任务" : task.getTitle(),
                    enterpriseNames.get(a.getEmployeeId()),
                    a.getStatus(),
                    task == null ? null : task.getStartAt(),
                    task == null ? null : task.getLearnDeadlineAt(),
                    a.getCompletedAt(),
                    cvIds.size(), coursesDone, sectionsAll, sectionsDone, hours(seconds),
                    best == null ? null : new LearningArchiveVo.ExamBrief(
                            best.scoreSum(), best.totalScore(), best.passed(), best.submittedAt())));
        }

        rows.sort(Comparator.comparingLong(LearningArchiveVo.TaskRow::assignmentId).reversed());
        return new LearningArchiveVo(
                new LearningArchiveVo.Summary(assignments.size(), taskCompleted,
                        courseTotal, courseCompleted, hours(totalSeconds), examPassed),
                rows);
    }

    /** 考试记录流水：跨任务、按交卷时间倒序分页；每次交卷一行，不合并。 */
    public PageResult<ExamRecordVo> exams(CurrentUser user, Long page, Long pageSize) {
        long p = page == null || page < 1 ? 1 : page;
        long size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, MAX_EXAM_PAGE_SIZE);

        LearnerContext learner = learnerGuard.requireLearner(user);
        List<TrainingAssignment> assignments = ownAssignments(learner);
        if (assignments.isEmpty()) {
            return PageResult.empty(p, size);
        }
        List<Long> assignmentIds = assignments.stream().map(TrainingAssignment::getId).toList();
        Map<Long, TrainingAssignment> assignmentById = assignments.stream()
                .collect(Collectors.toMap(TrainingAssignment::getId, a -> a));
        Map<Long, TrainingTask> tasks = taskMap(assignments);
        Map<Long, String> enterpriseNames = enterpriseNames(learner);

        List<ExamAttempt> submitted = attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                .in(ExamAttempt::getAssignmentId, assignmentIds)
                .eq(ExamAttempt::getStatus, "SUBMITTED"));
        if (submitted.isEmpty()) {
            return PageResult.empty(p, size);
        }

        Map<Long, String> paperTitles = paperTitleMap(submitted);
        // 最佳成绩按任务算一次，用于给流水行打「最佳」标记
        Map<Long, List<ExamAttempt>> byAssignment = submitted.stream()
                .collect(Collectors.groupingBy(ExamAttempt::getAssignmentId));
        Map<Long, Long> bestIdByAssignment = new HashMap<>();
        byAssignment.forEach((assignmentId, list) -> {
            ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(list);
            if (best != null) {
                bestIdByAssignment.put(assignmentId, best.attemptId());
            }
        });

        List<ExamAttempt> ordered = submitted.stream()
                .sorted(Comparator.comparing(ExamAttempt::getSubmittedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(Comparator.comparingLong(ExamAttempt::getId).reversed()))
                .toList();

        long total = ordered.size();
        List<ExamRecordVo> items = ordered.stream()
                .skip((p - 1) * size)
                .limit(size)
                .map(a -> {
                    TrainingAssignment assignment = assignmentById.get(a.getAssignmentId());
                    TrainingTask task = assignment == null ? null : tasks.get(assignment.getTaskId());
                    return new ExamRecordVo(
                            a.getId(), a.getAssignmentId(),
                            task == null ? "培训任务" : task.getTitle(),
                            assignment == null ? null : enterpriseNames.get(assignment.getEmployeeId()),
                            paperTitles.getOrDefault(a.getPaperId(), "试卷"),
                            a.getScoreSum(), a.getTotalScore(), a.getPassScore(),
                            Integer.valueOf(1).equals(a.getPassed()),
                            Integer.valueOf(1).equals(a.getAutoSubmitted()),
                            a.getStartedAt(), a.getSubmittedAt(),
                            Objects.equals(bestIdByAssignment.get(a.getAssignmentId()), a.getId()));
                })
                .toList();
        return PageResult.of(items, total, p, size);
    }

    // ---------- 内部 ----------

    private List<TrainingAssignment> ownAssignments(LearnerContext learner) {
        Set<Long> employeeIds = learner.employeeIds();
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        return assignmentMapper.selectList(Wrappers.<TrainingAssignment>lambdaQuery()
                .in(TrainingAssignment::getEmployeeId, employeeIds));
    }

    private Map<Long, TrainingTask> taskMap(List<TrainingAssignment> assignments) {
        List<Long> ids = assignments.stream().map(TrainingAssignment::getTaskId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectList(Wrappers.<TrainingTask>lambdaQuery().in(TrainingTask::getId, ids))
                .stream().collect(Collectors.toMap(TrainingTask::getId, t -> t));
    }

    /** employeeId → 企业名（一个账号可跨多个企业，逐条标注避免混淆）。 */
    private Map<Long, String> enterpriseNames(LearnerContext learner) {
        List<Long> ids = learner.employees().stream().map(Employee::getEnterpriseId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> byEnterprise = ids.isEmpty() ? Map.of()
                : enterpriseMapper.selectList(Wrappers.<Enterprise>lambdaQuery().in(Enterprise::getId, ids))
                .stream().collect(Collectors.toMap(Enterprise::getId, Enterprise::getName));
        Map<Long, String> result = new HashMap<>();
        for (Employee e : learner.employees()) {
            result.put(e.getId(), byEnterprise.get(e.getEnterpriseId()));
        }
        return result;
    }

    private Map<Long, List<Long>> packageCourseIds(Set<Long> packageVersionIds) {
        if (packageVersionIds.isEmpty()) {
            return Map.of();
        }
        return packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .in(PackageCourse::getPackageVersionId, packageVersionIds))
                .stream().collect(Collectors.groupingBy(PackageCourse::getPackageVersionId,
                        Collectors.mapping(PackageCourse::getCourseVersionId, Collectors.toList())));
    }

    private Map<Long, List<CourseVersionResource>> bindingsByCourse(Set<Long> courseVersionIds) {
        if (courseVersionIds.isEmpty()) {
            return Map.of();
        }
        return bindingMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                        .in(CourseVersionResource::getCourseVersionId, courseVersionIds))
                .stream().collect(Collectors.groupingBy(CourseVersionResource::getCourseVersionId));
    }

    private Map<Long, CourseResource> resourceMap(Map<Long, List<CourseVersionResource>> bindings) {
        Set<Long> ids = bindings.values().stream().flatMap(List::stream)
                .map(CourseVersionResource::getResourceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery().in(CourseResource::getId, ids))
                .stream().collect(Collectors.toMap(CourseResource::getId, r -> r));
    }

    private Map<Long, List<ExamAttempt>> submittedAttempts(List<Long> assignmentIds) {
        return attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                        .in(ExamAttempt::getAssignmentId, assignmentIds)
                        .eq(ExamAttempt::getStatus, "SUBMITTED"))
                .stream().collect(Collectors.groupingBy(ExamAttempt::getAssignmentId));
    }

    private Map<Long, String> paperTitleMap(List<ExamAttempt> attempts) {
        Set<Long> ids = attempts.stream().map(ExamAttempt::getPaperId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return paperMapper.selectList(Wrappers.<ExamPaper>lambdaQuery().in(ExamPaper::getId, ids))
                .stream().collect(Collectors.toMap(ExamPaper::getId, ExamPaper::getTitle));
    }

    private static double hours(int seconds) {
        return BigDecimal.valueOf(seconds / 3600.0).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
