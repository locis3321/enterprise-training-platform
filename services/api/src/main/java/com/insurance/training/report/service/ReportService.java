package com.insurance.training.report.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.exam.entity.ExamAttempt;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.exam.service.ExamAttemptService;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.learning.service.BusinessClock;
import com.insurance.training.report.vo.EnterpriseSummaryVo;
import com.insurance.training.report.vo.LearnerReportVo;
import com.insurance.training.report.vo.TaskSummaryVo;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.entity.InsuranceCompany;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import com.insurance.training.tenancy.mapper.InsuranceCompanyMapper;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 培训报表聚合（只读）。
 *
 * <p>数据范围一律由 {@link CurrentUser} 推导并**显式**拼进每条查询条件（D6），
 * 不使用 MyBatis 拦截器；前端传入的任何归属参数都不参与范围判定。
 *
 * <p>完成度直接读 {@code training_assignment.status}（服务端判定的权威结果，只进不退），
 * 报表不重算完成度，避免出现第二套口径。
 */
@Service
public class ReportService {

    private final TrainingTaskMapper taskMapper;
    private final TrainingAssignmentMapper assignmentMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final ResourceOpenLogMapper openLogMapper;
    private final ExamAttemptMapper attemptMapper;
    private final ExamPaperMapper paperMapper;
    private final EmployeeMapper employeeMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final InsuranceCompanyMapper companyMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final CourseVersionResourceMapper courseVersionResourceMapper;
    private final CourseSectionMapper courseSectionMapper;
    private final CourseResourceMapper courseResourceMapper;
    private final BusinessClock clock;
    private final int maxPageSize;

    public ReportService(TrainingTaskMapper taskMapper, TrainingAssignmentMapper assignmentMapper,
                         LearningRecordMapper learningRecordMapper, ResourceOpenLogMapper openLogMapper,
                         ExamAttemptMapper attemptMapper, ExamPaperMapper paperMapper,
                         EmployeeMapper employeeMapper, EnterpriseMapper enterpriseMapper,
                         InsuranceCompanyMapper companyMapper, PackageCourseMapper packageCourseMapper,
                         CourseVersionMapper courseVersionMapper,
                         CourseVersionResourceMapper courseVersionResourceMapper,
                         CourseSectionMapper courseSectionMapper, CourseResourceMapper courseResourceMapper,
                         BusinessClock clock, @Value("${app.page.max-size:200}") int maxPageSize) {
        this.taskMapper = taskMapper;
        this.assignmentMapper = assignmentMapper;
        this.learningRecordMapper = learningRecordMapper;
        this.openLogMapper = openLogMapper;
        this.attemptMapper = attemptMapper;
        this.paperMapper = paperMapper;
        this.employeeMapper = employeeMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.companyMapper = companyMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.courseVersionResourceMapper = courseVersionResourceMapper;
        this.courseSectionMapper = courseSectionMapper;
        this.courseResourceMapper = courseResourceMapper;
        this.clock = clock;
        this.maxPageSize = maxPageSize;
    }

    // ---------- 子公司汇总 ----------

    public PageResult<EnterpriseSummaryVo> enterpriseSummary(CurrentUser user, String keyword,
                                                             Long page, Long pageSize) {
        long p = checkPage(page);
        long size = checkPageSize(pageSize);

        var ew = Wrappers.<Enterprise>lambdaQuery();
        if (!user.isSuperAdmin()) {
            if (user.companyId() == null) {
                return PageResult.empty(p, size);
            }
            ew.eq(Enterprise::getInsuranceCompanyId, user.companyId());
            if (user.scope() == DataScopeType.ENTERPRISE) {
                ew.eq(Enterprise::getId, user.enterpriseId());
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            ew.like(Enterprise::getName, keyword.trim());
        }
        ew.orderByAsc(Enterprise::getId);

        List<Enterprise> all = enterpriseMapper.selectList(ew);
        long total = all.size();
        List<Enterprise> pageRows = all.stream().skip((p - 1) * size).limit(size).toList();
        if (pageRows.isEmpty()) {
            return PageResult.of(List.of(), total, p, size);
        }
        return PageResult.of(enterpriseRows(pageRows), total, p, size);
    }

    /** 导出用：不分页，行数上限由调用方控制。 */
    public PageResult<EnterpriseSummaryVo> enterpriseSummaryAll(CurrentUser user, String keyword) {
        var ew = Wrappers.<Enterprise>lambdaQuery();
        if (!user.isSuperAdmin()) {
            if (user.companyId() == null) {
                return PageResult.of(List.of(), 0, 1, 1);
            }
            ew.eq(Enterprise::getInsuranceCompanyId, user.companyId());
            if (user.scope() == DataScopeType.ENTERPRISE) {
                ew.eq(Enterprise::getId, user.enterpriseId());
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            ew.like(Enterprise::getName, keyword.trim());
        }
        ew.orderByAsc(Enterprise::getId);
        List<Enterprise> all = enterpriseMapper.selectList(ew);
        return PageResult.of(enterpriseRows(all), all.size(), 1, Math.max(1, all.size()));
    }

    private List<EnterpriseSummaryVo> enterpriseRows(List<Enterprise> pageRows) {
        Map<Long, String> companyNames = companyNameMap(pageRows);
        List<Long> pageIds = pageRows.stream().map(Enterprise::getId).toList();
        Map<Long, List<TrainingAssignment>> assignmentsByEnterprise = assignmentsByEnterprise(pageIds);
        List<TrainingAssignment> allRows = assignmentsByEnterprise.values().stream()
                .flatMap(List::stream).toList();

        Map<Long, Integer> totalSecondsByAssignment = totalSecondsByAssignment(
                allRows.stream().map(TrainingAssignment::getId).toList());
        Map<Long, List<ExamAttempt>> attemptsByAssignment = submittedAttempts(
                allRows.stream().map(TrainingAssignment::getId).toList());
        Map<Long, LocalDateTime> deadlines = deadlines(allRows);

        List<EnterpriseSummaryVo> items = new ArrayList<>(pageRows.size());
        for (Enterprise e : pageRows) {
            List<TrainingAssignment> rows = assignmentsByEnterprise.getOrDefault(e.getId(), List.of());
            int assigned = (int) rows.stream().map(TrainingAssignment::getEmployeeId).distinct().count();
            int completed = (int) rows.stream().filter(r -> "COMPLETED".equals(r.getStatus())).count();
            int seconds = rows.stream()
                    .mapToInt(r -> totalSecondsByAssignment.getOrDefault(r.getId(), 0)).sum();
            ExamStat exam = examStat(rows, attemptsByAssignment);
            items.add(new EnterpriseSummaryVo(e.getId(), e.getName(),
                    companyNames.get(e.getInsuranceCompanyId()),
                    assigned, completed, rate(completed, assigned), hours(seconds),
                    exam.takers(), exam.passed(), rate(exam.passed(), exam.takers()),
                    overdue(rows, deadlines)));
        }
        return items;
    }

    // ---------- 任务汇总 ----------

    public PageResult<TaskSummaryVo> taskSummary(CurrentUser user, Long enterpriseId, String keyword,
                                                 Long page, Long pageSize) {
        long p = checkPage(page);
        long size = checkPageSize(pageSize);

        var tw = Wrappers.<TrainingTask>lambdaQuery();
        if (user.isSuperAdmin()) {
            if (enterpriseId != null) {
                tw.eq(TrainingTask::getEnterpriseId, enterpriseId);
            }
        }
        else {
            if (user.companyId() == null) {
                return PageResult.empty(p, size);
            }
            tw.eq(TrainingTask::getInsuranceCompanyId, user.companyId());
            if (user.scope() == DataScopeType.ENTERPRISE) {
                tw.eq(TrainingTask::getEnterpriseId, user.enterpriseId());
            }
            else if (enterpriseId != null) {
                tw.eq(TrainingTask::getEnterpriseId, enterpriseId);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            tw.like(TrainingTask::getTitle, keyword.trim());
        }
        tw.orderByDesc(TrainingTask::getId);

        List<TrainingTask> all = taskMapper.selectList(tw);
        long total = all.size();
        List<TrainingTask> pageRows = all.stream().skip((p - 1) * size).limit(size).toList();
        if (pageRows.isEmpty()) {
            return PageResult.of(List.of(), total, p, size);
        }
        return PageResult.of(taskRows(pageRows), total, p, size);
    }

    /** 导出用：不分页。 */
    public PageResult<TaskSummaryVo> taskSummaryAll(CurrentUser user, Long enterpriseId, String keyword) {
        var tw = Wrappers.<TrainingTask>lambdaQuery();
        if (user.isSuperAdmin()) {
            if (enterpriseId != null) {
                tw.eq(TrainingTask::getEnterpriseId, enterpriseId);
            }
        }
        else {
            if (user.companyId() == null) {
                return PageResult.of(List.of(), 0, 1, 1);
            }
            tw.eq(TrainingTask::getInsuranceCompanyId, user.companyId());
            if (user.scope() == DataScopeType.ENTERPRISE) {
                tw.eq(TrainingTask::getEnterpriseId, user.enterpriseId());
            }
            else if (enterpriseId != null) {
                tw.eq(TrainingTask::getEnterpriseId, enterpriseId);
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            tw.like(TrainingTask::getTitle, keyword.trim());
        }
        tw.orderByDesc(TrainingTask::getId);
        List<TrainingTask> all = taskMapper.selectList(tw);
        return PageResult.of(taskRows(all), all.size(), 1, Math.max(1, all.size()));
    }

    private List<TaskSummaryVo> taskRows(List<TrainingTask> pageRows) {
        List<Long> taskIds = pageRows.stream().map(TrainingTask::getId).toList();
        Map<Long, List<TrainingAssignment>> assignmentsByTask = assignmentMapper.selectList(
                        Wrappers.<TrainingAssignment>lambdaQuery().in(TrainingAssignment::getTaskId, taskIds))
                .stream().collect(Collectors.groupingBy(TrainingAssignment::getTaskId));
        List<Long> enterpriseIds = pageRows.stream().map(TrainingTask::getEnterpriseId).distinct().toList();
        Map<Long, String> enterpriseNames = enterpriseNameMap(enterpriseIds);

        List<TrainingAssignment> allRows = assignmentsByTask.values().stream().flatMap(List::stream).toList();
        Map<Long, List<ExamAttempt>> attemptsByAssignment = submittedAttempts(
                allRows.stream().map(TrainingAssignment::getId).toList());
        Map<Long, LocalDateTime> deadlines = pageRows.stream()
                .filter(t -> t.getLearnDeadlineAt() != null)
                .collect(Collectors.toMap(TrainingTask::getId, TrainingTask::getLearnDeadlineAt));

        List<TaskSummaryVo> items = new ArrayList<>(pageRows.size());
        for (TrainingTask t : pageRows) {
            List<TrainingAssignment> rows = assignmentsByTask.getOrDefault(t.getId(), List.of());
            int assigned = (int) rows.stream().map(TrainingAssignment::getEmployeeId).distinct().count();
            int completed = (int) rows.stream().filter(r -> "COMPLETED".equals(r.getStatus())).count();
            ExamStat exam = examStat(rows, attemptsByAssignment);
            items.add(new TaskSummaryVo(t.getId(), t.getTitle(), t.getEnterpriseId(),
                    enterpriseNames.get(t.getEnterpriseId()), t.getPackageVersionId(),
                    t.getStartAt(), t.getLearnDeadlineAt(), t.getStatus(),
                    assigned, completed, rate(completed, assigned),
                    exam.takers(), exam.passed(), rate(exam.passed(), exam.takers()),
                    overdue(rows, deadlines)));
        }
        return items;
    }

    // ---------- 个人明细 ----------

    public LearnerReportVo learner(CurrentUser user, long employeeId) {
        Employee employee = employeeMapper.selectById(employeeId);
        if (employee == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "员工不存在");
        }
        checkEmployeeVisible(user, employee);

        List<TrainingAssignment> assignments = assignmentMapper.selectList(
                Wrappers.<TrainingAssignment>lambdaQuery()
                        .eq(TrainingAssignment::getEmployeeId, employeeId)
                        .orderByDesc(TrainingAssignment::getId));
        List<Long> assignmentIds = assignments.stream().map(TrainingAssignment::getId).toList();

        Map<Long, TrainingTask> tasks = taskMap(
                assignments.stream().map(TrainingAssignment::getTaskId).distinct().toList());
        Map<Long, List<LearningRecord>> recordsByAssignment = recordsByAssignment(assignmentIds);
        Map<Long, Set<Long>> openedByAssignment = openedByAssignment(assignmentIds);
        Map<Long, List<ExamAttempt>> attemptsByAssignment = submittedAttempts(assignmentIds);
        Map<Long, String> paperTitles = paperTitleMap(attemptsByAssignment.values().stream()
                .flatMap(List::stream).map(ExamAttempt::getPaperId).collect(Collectors.toSet()));

        // 一个培训包版本包含哪些课程版本
        Set<Long> packageVersionIds = assignments.stream().map(TrainingAssignment::getPackageVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<Long>> courseVersionIdsByPackage = packageCourseVersionIds(packageVersionIds);
        Set<Long> courseVersionIds = new HashSet<>(courseVersionIdsByPackage.values().stream()
                .flatMap(List::stream).toList());

        Map<Long, CourseVersion> versions = courseVersionMap(courseVersionIds);
        Map<Long, List<CourseVersionResource>> bindingsByVersion = bindingsByVersion(courseVersionIds);
        Map<Long, CourseSection> sections = sectionMap(bindingsByVersion.values().stream()
                .flatMap(List::stream).map(CourseVersionResource::getSectionId).collect(Collectors.toSet()));
        Map<Long, Integer> resourceDurations = resourceDurationMap(bindingsByVersion.values().stream()
                .flatMap(List::stream).map(CourseVersionResource::getResourceId).collect(Collectors.toSet()));

        Enterprise enterprise = enterpriseMapper.selectById(employee.getEnterpriseId());

        List<LearnerReportVo.TaskDetail> taskDetails = new ArrayList<>();
        for (TrainingAssignment a : assignments) {
            TrainingTask task = tasks.get(a.getTaskId());
            List<LearningRecord> records = recordsByAssignment.getOrDefault(a.getId(), List.of());
            Set<Long> opened = openedByAssignment.getOrDefault(a.getId(), Set.of());
            Map<Long, LearningRecord> recordByResource = records.stream()
                    .filter(r -> r.getResourceId() != null)
                    .collect(Collectors.toMap(LearningRecord::getResourceId, r -> r, (x, y) -> x));

            List<LearnerReportVo.CourseDetail> courses = new ArrayList<>();
            int assignmentSeconds = 0;
            for (Long cvId : courseVersionIdsByPackage.getOrDefault(a.getPackageVersionId(), List.of())) {
                CourseVersion cv = versions.get(cvId);
                if (cv == null) {
                    continue;
                }
                Map<Long, List<CourseVersionResource>> bySection = bindingsByVersion
                        .getOrDefault(cvId, List.of()).stream()
                        .collect(Collectors.groupingBy(CourseVersionResource::getSectionId));
                List<LearnerReportVo.SectionDetail> sectionRows = new ArrayList<>();
                int courseSeconds = 0;
                for (Map.Entry<Long, List<CourseVersionResource>> entry : bySection.entrySet()) {
                    List<CourseVersionResource> binds = entry.getValue();
                    int required = binds.stream()
                            .mapToInt(b -> resourceDurations.getOrDefault(b.getResourceId(), 0)).sum();
                    boolean allDone = binds.stream().allMatch(b -> {
                        LearningRecord r = recordByResource.get(b.getResourceId());
                        boolean videoDone = r != null && Integer.valueOf(1).equals(r.getCompleted());
                        return videoDone || opened.contains(b.getResourceId());
                    });
                    int effective = binds.stream().mapToInt(b -> {
                        LearningRecord r = recordByResource.get(b.getResourceId());
                        return r == null || r.getEffectiveSeconds() == null ? 0 : r.getEffectiveSeconds();
                    }).sum();
                    courseSeconds += effective;
                    CourseSection cs = sections.get(entry.getKey());
                    sectionRows.add(new LearnerReportVo.SectionDetail(entry.getKey(),
                            cs == null ? "章节 " + entry.getKey() : cs.getTitle(),
                            allDone, effective, required));
                }
                sectionRows.sort(Comparator.comparingInt(
                        s -> sectionSortNo(sections.get(s.sectionId()))));
                assignmentSeconds += courseSeconds;
                courses.add(new LearnerReportVo.CourseDetail(cvId, cv.getTitle(), courseSeconds, sectionRows));
            }

            List<ExamAttempt> submitted = attemptsByAssignment.getOrDefault(a.getId(), List.of());
            ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(submitted);
            Long bestId = best == null ? null : best.attemptId();
            List<LearnerReportVo.AttemptRow> attemptRows = submitted.stream()
                    .sorted(Comparator.comparing(ExamAttempt::getSubmittedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                    .map(at -> new LearnerReportVo.AttemptRow(at.getId(),
                            paperTitles.getOrDefault(at.getPaperId(), "试卷 " + at.getPaperId()),
                            at.getScoreSum(), at.getTotalScore(), at.getPassScore(),
                            Integer.valueOf(1).equals(at.getPassed()),
                            at.getStartedAt(), at.getSubmittedAt(),
                            Integer.valueOf(1).equals(at.getAutoSubmitted()),
                            Objects.equals(bestId, at.getId())))
                    .toList();

            taskDetails.add(new LearnerReportVo.TaskDetail(a.getTaskId(),
                    task == null ? "任务 " + a.getTaskId() : task.getTitle(),
                    a.getStatus(), a.getStartedAt(), a.getCompletedAt(), assignmentSeconds,
                    courses, attemptRows, bestId));
        }

        return new LearnerReportVo(employee.getId(), employee.getName(), maskPhone(employee.getPhone()),
                employee.getEnterpriseId(), enterprise == null ? null : enterprise.getName(), taskDetails);
    }

    // ---------- 范围与工具 ----------

    /** 员工可见性：越权统一 403，不返回 404，避免探测。 */
    private void checkEmployeeVisible(CurrentUser user, Employee employee) {
        if (user.isSuperAdmin()) {
            return;
        }
        if (user.companyId() == null
                || !Objects.equals(user.companyId(), employee.getInsuranceCompanyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看其他集团的数据");
        }
        if (user.scope() == DataScopeType.ENTERPRISE
                && !Objects.equals(user.enterpriseId(), employee.getEnterpriseId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看其他子公司的数据");
        }
    }

    private long checkPage(Long page) {
        long p = page == null ? 1 : page;
        if (p < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须从 1 开始");
        }
        return p;
    }

    private long checkPageSize(Long pageSize) {
        long size = pageSize == null ? 10 : pageSize;
        if (size < 1 || size > maxPageSize) {
            throw new BizException(ErrorCode.BAD_REQUEST, "pageSize 必须在 1-" + maxPageSize + " 之间");
        }
        return size;
    }

    private Map<Long, String> companyNameMap(List<Enterprise> rows) {
        List<Long> ids = rows.stream().map(Enterprise::getInsuranceCompanyId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectList(Wrappers.<InsuranceCompany>lambdaQuery().in(InsuranceCompany::getId, ids))
                .stream().collect(Collectors.toMap(InsuranceCompany::getId, InsuranceCompany::getName));
    }

    private Map<Long, String> enterpriseNameMap(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return enterpriseMapper.selectList(Wrappers.<Enterprise>lambdaQuery().in(Enterprise::getId, ids))
                .stream().collect(Collectors.toMap(Enterprise::getId, Enterprise::getName));
    }

    private Map<Long, List<TrainingAssignment>> assignmentsByEnterprise(List<Long> enterpriseIds) {
        if (enterpriseIds.isEmpty()) {
            return Map.of();
        }
        List<TrainingTask> tasks = taskMapper.selectList(
                Wrappers.<TrainingTask>lambdaQuery().in(TrainingTask::getEnterpriseId, enterpriseIds));
        if (tasks.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> taskToEnterprise = tasks.stream()
                .collect(Collectors.toMap(TrainingTask::getId, TrainingTask::getEnterpriseId));
        return assignmentMapper.selectList(Wrappers.<TrainingAssignment>lambdaQuery()
                        .in(TrainingAssignment::getTaskId, taskToEnterprise.keySet()))
                .stream().collect(Collectors.groupingBy(a -> taskToEnterprise.get(a.getTaskId())));
    }

    /** assignmentId → 有效学时秒数合计。 */
    private Map<Long, Integer> totalSecondsByAssignment(List<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (LearningRecord r : learningRecordMapper.selectList(Wrappers.<LearningRecord>lambdaQuery()
                .in(LearningRecord::getAssignmentId, assignmentIds))) {
            int seconds = r.getEffectiveSeconds() == null ? 0 : r.getEffectiveSeconds();
            result.merge(r.getAssignmentId(), seconds, Integer::sum);
        }
        return result;
    }

    private Map<Long, List<ExamAttempt>> submittedAttempts(List<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        return attemptMapper.selectList(Wrappers.<ExamAttempt>lambdaQuery()
                        .in(ExamAttempt::getAssignmentId, assignmentIds)
                        .eq(ExamAttempt::getStatus, "SUBMITTED"))
                .stream().collect(Collectors.groupingBy(ExamAttempt::getAssignmentId));
    }

    private Map<Long, LocalDateTime> deadlines(List<TrainingAssignment> rows) {
        List<Long> taskIds = rows.stream().map(TrainingAssignment::getTaskId).distinct().toList();
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectList(Wrappers.<TrainingTask>lambdaQuery().in(TrainingTask::getId, taskIds))
                .stream().filter(t -> t.getLearnDeadlineAt() != null)
                .collect(Collectors.toMap(TrainingTask::getId, TrainingTask::getLearnDeadlineAt));
    }

    private Map<Long, TrainingTask> taskMap(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return taskMapper.selectList(Wrappers.<TrainingTask>lambdaQuery().in(TrainingTask::getId, ids))
                .stream().collect(Collectors.toMap(TrainingTask::getId, t -> t));
    }

    private Map<Long, List<LearningRecord>> recordsByAssignment(List<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        return learningRecordMapper.selectList(Wrappers.<LearningRecord>lambdaQuery()
                        .in(LearningRecord::getAssignmentId, assignmentIds))
                .stream().collect(Collectors.groupingBy(LearningRecord::getAssignmentId));
    }

    private Map<Long, Set<Long>> openedByAssignment(List<Long> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        return openLogMapper.selectList(Wrappers.<ResourceOpenLog>lambdaQuery()
                        .in(ResourceOpenLog::getAssignmentId, assignmentIds))
                .stream().collect(Collectors.groupingBy(ResourceOpenLog::getAssignmentId,
                        Collectors.mapping(ResourceOpenLog::getResourceId, Collectors.toSet())));
    }

    private Map<Long, List<Long>> packageCourseVersionIds(Set<Long> packageVersionIds) {
        if (packageVersionIds.isEmpty()) {
            return Map.of();
        }
        return packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                        .in(PackageCourse::getPackageVersionId, packageVersionIds))
                .stream().collect(Collectors.groupingBy(PackageCourse::getPackageVersionId,
                        Collectors.mapping(PackageCourse::getCourseVersionId, Collectors.toList())));
    }

    private Map<Long, CourseVersion> courseVersionMap(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                        .in(CourseVersion::getId, ids))
                .stream().collect(Collectors.toMap(CourseVersion::getId, v -> v));
    }

    private Map<Long, List<CourseVersionResource>> bindingsByVersion(Set<Long> courseVersionIds) {
        if (courseVersionIds.isEmpty()) {
            return Map.of();
        }
        return courseVersionResourceMapper.selectList(Wrappers.<CourseVersionResource>lambdaQuery()
                        .in(CourseVersionResource::getCourseVersionId, courseVersionIds))
                .stream().collect(Collectors.groupingBy(CourseVersionResource::getCourseVersionId));
    }

    private Map<Long, CourseSection> sectionMap(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courseSectionMapper.selectList(Wrappers.<CourseSection>lambdaQuery().in(CourseSection::getId, ids))
                .stream().collect(Collectors.toMap(CourseSection::getId, s -> s));
    }

    /** 章节应看时长取该章节全部资源的 duration_seconds 之和（V6：不读 course_section.duration_seconds）。 */
    private Map<Long, Integer> resourceDurationMap(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return courseResourceMapper.selectList(Wrappers.<CourseResource>lambdaQuery().in(CourseResource::getId, ids))
                .stream().collect(Collectors.toMap(CourseResource::getId,
                        r -> r.getDurationSeconds() == null ? 0 : r.getDurationSeconds()));
    }

    private Map<Long, String> paperTitleMap(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return paperMapper.selectList(Wrappers.<ExamPaper>lambdaQuery().in(ExamPaper::getId, ids))
                .stream().collect(Collectors.toMap(ExamPaper::getId, ExamPaper::getTitle));
    }

    private static int sectionSortNo(CourseSection section) {
        return section == null || section.getSortNo() == null ? 0 : section.getSortNo();
    }

    /** 按人取最佳成绩，统计参考人数与通过人数（不按人次）。 */
    private ExamStat examStat(List<TrainingAssignment> rows, Map<Long, List<ExamAttempt>> attemptsByAssignment) {
        int takers = 0;
        int passed = 0;
        for (TrainingAssignment row : rows) {
            List<ExamAttempt> submitted = attemptsByAssignment.get(row.getId());
            if (submitted == null || submitted.isEmpty()) {
                continue;
            }
            takers++;
            ExamAttemptService.BestAttempt best = ExamAttemptService.pickBest(submitted);
            if (best != null && best.passed()) {
                passed++;
            }
        }
        return new ExamStat(takers, passed);
    }

    private int overdue(List<TrainingAssignment> rows, Map<Long, LocalDateTime> deadlines) {
        LocalDateTime now = clock.now();
        return (int) rows.stream()
                .filter(r -> !"COMPLETED".equals(r.getStatus()))
                .filter(r -> {
                    LocalDateTime d = deadlines.get(r.getTaskId());
                    return d != null && d.isBefore(now);
                }).count();
    }

    private static double rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double hours(int seconds) {
        return BigDecimal.valueOf(seconds / 3600.0).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private record ExamStat(int takers, int passed) {
    }
}
