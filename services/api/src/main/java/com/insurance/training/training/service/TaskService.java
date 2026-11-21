package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.config.JacksonConfig;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.web.PageQuery;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.tenancy.entity.Enterprise;
import com.insurance.training.tenancy.service.EnterpriseService;
import com.insurance.training.training.dto.TaskRequest;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import com.insurance.training.training.vo.TaskAssignmentVo;
import com.insurance.training.training.vo.TaskDetailVo;
import com.insurance.training.training.vo.TaskProgressVo;
import com.insurance.training.training.vo.TaskVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 培训任务的创建、列表、关闭与进度。 */
@Service
public class TaskService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of(JacksonConfig.BUSINESS_TIME_ZONE);

    private final TrainingTaskMapper taskMapper;
    private final TrainingPackageVersionMapper versionMapper;
    private final TrainingPackageMapper packageMapper;
    private final PackageCourseMapper packageCourseMapper;
    private final CourseVersionMapper courseVersionMapper;
    private final CourseMapper courseMapper;
    private final EnterpriseService enterpriseService;
    private final EmployeeMapper employeeMapper;
    private final TrainingAssignmentMapper assignmentMapper;
    private final LearningRecordMapper learningRecordMapper;
    private final AssignmentService assignmentService;
    private final AuditService auditService;
    private final int maxPageSize;

    public TaskService(TrainingTaskMapper taskMapper, TrainingPackageVersionMapper versionMapper,
                       TrainingPackageMapper packageMapper, PackageCourseMapper packageCourseMapper,
                       CourseVersionMapper courseVersionMapper, CourseMapper courseMapper,
                       EnterpriseService enterpriseService, EmployeeMapper employeeMapper,
                       TrainingAssignmentMapper assignmentMapper, LearningRecordMapper learningRecordMapper,
                       AssignmentService assignmentService,
                       AuditService auditService, @Value("${app.page.max-size:200}") int maxPageSize) {
        this.taskMapper = taskMapper;
        this.versionMapper = versionMapper;
        this.packageMapper = packageMapper;
        this.packageCourseMapper = packageCourseMapper;
        this.courseVersionMapper = courseVersionMapper;
        this.courseMapper = courseMapper;
        this.enterpriseService = enterpriseService;
        this.employeeMapper = employeeMapper;
        this.assignmentMapper = assignmentMapper;
        this.learningRecordMapper = learningRecordMapper;
        this.assignmentService = assignmentService;
        this.auditService = auditService;
        this.maxPageSize = maxPageSize;
    }

    /**
     * 建任务即发布，并在同一事务内完成分配。
     *
     * <p>分配是**一次性快照**：{@code assignAll} 只取当时启用的员工，后续新导入员工不会自动获得任务。
     */
    @Transactional
    public TaskVo create(CurrentUser user, TaskRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        if (!request.startAt().isBefore(request.learnDeadlineAt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开始时间必须早于学习截止时间");
        }
        Enterprise enterprise = enterpriseService.getVisible(user, request.enterpriseId());
        TrainingPackageVersion packageVersion = requireUsablePackageVersion(enterprise, request.packageVersionId());

        List<Employee> employees = resolveEmployees(request, enterprise);
        if (employees.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未选出任何可用员工，请检查分配范围");
        }

        TrainingTask task = new TrainingTask();
        task.setInsuranceCompanyId(enterprise.getInsuranceCompanyId());
        task.setEnterpriseId(enterprise.getId());
        task.setPackageVersionId(packageVersion.getId());
        task.setTitle(request.title().trim());
        task.setStartAt(request.startAt());
        task.setLearnDeadlineAt(request.learnDeadlineAt());
        task.setStatus("PUBLISHED");
        task.setCreatedByAdminId(user.id());
        taskMapper.insert(task);

        assignmentService.assign(task, employees, user, ip);

        auditService.record(user, "TASK_CREATED", "training_task", task.getId(),
                "enterpriseId=" + enterprise.getId() + ",packageVersionId=" + packageVersion.getId()
                        + ",assignees=" + employees.size(), ip);
        return TaskVo.of(task);
    }

    /**
     * 给已完成/进行中的原任务追加一个**新版本**的新任务，分配给原任务的同一批学员。
     *
     * <p>为什么是新建任务而不是改原任务版本：`training_task.package_version_id` 是不可变快照
     * （AGENTS.md 硬约束），改版本会让既有 learning_record 找不到对应章节，进度重置且可能
     * 违反「只进不退」。新建任务保留旧任务 100% 记录，学员会同时看到旧任务与新任务。
     *
     * <p>拒绝与原任务同版本：同版本追加没有任何新内容，属调用错误。
     */
    @Transactional
    public TaskVo createSuccessor(CurrentUser user, long taskId, TaskRequest request, String ip) {
        TrainingTask origin = getWritable(user, taskId);
        if (request.packageVersionId().equals(origin.getPackageVersionId())) {
            throw new BizException(ErrorCode.CONFLICT, "新任务必须使用与原任务不同的培训包版本");
        }
        if (!request.enterpriseId().equals(origin.getEnterpriseId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "追加任务必须与原任务属于同一企业");
        }
        if (!request.startAt().isBefore(request.learnDeadlineAt())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开始时间必须早于学习截止时间");
        }
        Enterprise enterprise = enterpriseService.getVisible(user, request.enterpriseId());
        TrainingPackageVersion packageVersion = requireUsablePackageVersion(enterprise, request.packageVersionId());

        List<TrainingAssignment> originAssignments = assignmentMapper.selectList(
                Wrappers.<TrainingAssignment>lambdaQuery().eq(TrainingAssignment::getTaskId, taskId));
        if (originAssignments.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "原任务没有任何学员分配，无法追加");
        }
        List<Long> employeeIds = originAssignments.stream().map(TrainingAssignment::getEmployeeId)
                .distinct().toList();
        List<Employee> employees = loadEmployees(enterprise.getId(), employeeIds);
        if (employees.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "原任务的学员已全部停用或调离，无法追加");
        }

        TrainingTask task = new TrainingTask();
        task.setInsuranceCompanyId(enterprise.getInsuranceCompanyId());
        task.setEnterpriseId(enterprise.getId());
        task.setPackageVersionId(packageVersion.getId());
        task.setTitle(request.title().trim());
        task.setStartAt(request.startAt());
        task.setLearnDeadlineAt(request.learnDeadlineAt());
        task.setStatus("PUBLISHED");
        task.setCreatedByAdminId(user.id());
        taskMapper.insert(task);

        assignmentService.assign(task, employees, user, ip);
        auditService.record(user, "TASK_SUCCESSOR_CREATED", "training_task", task.getId(),
                "originTaskId=" + taskId + ",packageVersionId=" + packageVersion.getId()
                        + ",assignees=" + employees.size(), ip);
        return TaskVo.of(task);
    }

    /** 列表：超管全平台；公司管理员本公司；企业管理员本公司且本企业。 */
    public PageResult<TaskVo> page(CurrentUser user, Long enterpriseId, String status, String keyword,
                                   Long page, Long pageSize) {
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<TrainingTask>lambdaQuery().orderByDesc(TrainingTask::getId);

        if (!user.isSuperAdmin()) {
            if (user.companyId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(TrainingTask::getInsuranceCompanyId, user.companyId());
            if (user.scope() == DataScopeType.ENTERPRISE) {
                wrapper.eq(TrainingTask::getEnterpriseId, user.enterpriseId());
            }
        }
        if (enterpriseId != null) {
            wrapper.eq(TrainingTask::getEnterpriseId, enterpriseId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(TrainingTask::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TrainingTask::getTitle, keyword.trim());
        }

        Page<TrainingTask> result = taskMapper.selectPage(new Page<>(pq.page(), pq.pageSize()), wrapper);
        List<TaskVo> items = result.getRecords().stream().map(TaskVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    /** 详情：任务 + 进度统计。 */
    public TaskDetailVo detail(CurrentUser user, long taskId) {
        TrainingTask task = getVisible(user, taskId);
        return new TaskDetailVo(TaskVo.of(task), progress(user, taskId));
    }

    /** 追加分配：只允许自行选择员工，且任务必须仍为 PUBLISHED。 */
    @Transactional
    public int assignMore(CurrentUser user, long taskId, List<Long> employeeIds, String ip) {
        TrainingTask task = getWritable(user, taskId);
        if (employeeIds == null || employeeIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一个员工");
        }
        List<Employee> employees = loadEmployees(task.getEnterpriseId(), employeeIds);
        return assignmentService.assign(task, employees, user, ip);
    }

    /** 关闭任务：状态 CAS，已关闭/已变更一律 409。 */
    @Transactional
    public TaskVo close(CurrentUser user, long taskId, String ip) {
        TrainingTask task = getWritable(user, taskId);
        if (!"PUBLISHED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有进行中的任务可关闭");
        }
        int updated = taskMapper.update(null, Wrappers.<TrainingTask>lambdaUpdate()
                .set(TrainingTask::getStatus, "CLOSED")
                .eq(TrainingTask::getId, task.getId())
                .eq(TrainingTask::getStatus, "PUBLISHED"));
        if (updated == 0) {
            throw new BizException(ErrorCode.CONFLICT, "任务状态已被其他操作改变，请刷新后重试");
        }
        task.setStatus("CLOSED");
        auditService.record(user, "TASK_CLOSED", "training_task", taskId, null, ip);
        return TaskVo.of(task);
    }

    /** 进度：总人数 / 已完成 / 进行中 / 未开始。 */
    public TaskProgressVo progress(CurrentUser user, long taskId) {
        getVisible(user, taskId);
        return new TaskProgressVo(
                countAssignments(taskId, null),
                countAssignments(taskId, "COMPLETED"),
                countAssignments(taskId, "IN_PROGRESS"),
                countAssignments(taskId, "NOT_STARTED"));
    }

    /**
     * 逐人分配明细。
     *
     * <p>时长取服务端累计的 learning_record 之和，不读任何客户端上报值。
     * 只读接口，仍需走可见范围校验（跨公司/跨企业不可见）。
     */
    public List<TaskAssignmentVo> assignmentRows(CurrentUser user, long taskId) {
        getVisible(user, taskId);

        List<TrainingAssignment> assignments = assignmentMapper.selectList(
                Wrappers.<TrainingAssignment>lambdaQuery()
                        .eq(TrainingAssignment::getTaskId, taskId)
                        .orderByAsc(TrainingAssignment::getId));
        if (assignments.isEmpty()) {
            return List.of();
        }
        List<Long> employeeIds = assignments.stream().map(TrainingAssignment::getEmployeeId).distinct().toList();
        Map<Long, String> nameById = employeeMapper.selectList(Wrappers.<Employee>lambdaQuery()
                        .in(Employee::getId, employeeIds))
                .stream().collect(Collectors.toMap(Employee::getId, Employee::getName));

        List<Long> assignmentIds = assignments.stream().map(TrainingAssignment::getId).toList();
        Map<Long, Integer> secondsByAssignment = learningRecordMapper.selectList(
                        Wrappers.<LearningRecord>lambdaQuery().in(LearningRecord::getAssignmentId, assignmentIds))
                .stream().collect(Collectors.groupingBy(LearningRecord::getAssignmentId,
                        Collectors.summingInt(r -> r.getEffectiveSeconds() == null ? 0 : r.getEffectiveSeconds())));

        return assignments.stream()
                .map(a -> new TaskAssignmentVo(a.getId(), a.getEmployeeId(),
                        nameById.getOrDefault(a.getEmployeeId(), "员工 " + a.getEmployeeId()),
                        a.getStatus(), secondsByAssignment.getOrDefault(a.getId(), 0),
                        a.getStartedAt(), a.getCompletedAt()))
                .toList();
    }

    // ---------- 内部 ----------

    public TrainingTask getVisible(CurrentUser user, long taskId) {
        TrainingTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        DataScopeGuard.checkEnterprise(user, task.getInsuranceCompanyId(), task.getEnterpriseId());
        return task;
    }

    private TrainingTask getWritable(CurrentUser user, long taskId) {
        TrainingTask task = getVisible(user, taskId);
        DataScopeGuard.requireAdmin();
        return task;
    }

    /**
     * 培训包版本必须可被新任务使用：版本 PUBLISHED、所属包未下线、
     * 且包内每个课程版本对应的课程未被逻辑删除、未 OFFLINE。
     *
     * <p>OFFLINE 校验放在建任务而非制包时：课程可能在包发布之后才下线，制包侧检查挡不住。
     */
    private TrainingPackageVersion requireUsablePackageVersion(Enterprise enterprise, long packageVersionId) {
        TrainingPackageVersion version = versionMapper.selectById(packageVersionId);
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训包版本不存在");
        }
        if (!"PUBLISHED".equals(version.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只能使用已发布的培训包版本");
        }
        TrainingPackage pkg = packageMapper.selectById(version.getPackageId());
        if (pkg == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "培训包不存在");
        }
        if (!pkg.getInsuranceCompanyId().equals(enterprise.getInsuranceCompanyId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能给其他集团的子公司下发培训包");
        }
        if (!"PUBLISHED".equals(pkg.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "培训包已下线，不能用于新建任务");
        }

        List<PackageCourse> links = packageCourseMapper.selectList(Wrappers.<PackageCourse>lambdaQuery()
                .eq(PackageCourse::getPackageVersionId, version.getId()));
        if (links.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "培训包版本没有任何课程，不能用于新建任务");
        }
        List<Long> versionIds = links.stream().map(PackageCourse::getCourseVersionId).distinct().toList();
        List<CourseVersion> courseVersions = courseVersionMapper.selectList(Wrappers.<CourseVersion>lambdaQuery()
                .in(CourseVersion::getId, versionIds));
        if (courseVersions.size() != versionIds.size()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "培训包内课程版本缺失，数据异常");
        }
        List<Long> courseIds = courseVersions.stream().map(CourseVersion::getCourseId).distinct().toList();
        List<Course> courses = courseMapper.selectList(Wrappers.<Course>lambdaQuery()
                .in(Course::getId, courseIds));
        if (courses.size() != courseIds.size()) {
            throw new BizException(ErrorCode.CONFLICT, "培训包含已删除课程，请重新制包");
        }
        boolean hasOffline = courses.stream().anyMatch(c -> "OFFLINE".equals(c.getStatus()));
        if (hasOffline) {
            throw new BizException(ErrorCode.CONFLICT, "培训包含已下线课程，请重新制包");
        }
        return version;
    }

    private List<Employee> resolveEmployees(TaskRequest request, Enterprise enterprise) {
        if (Boolean.TRUE.equals(request.assignAll())) {
            return employeeMapper.selectList(Wrappers.<Employee>lambdaQuery()
                    .eq(Employee::getEnterpriseId, enterprise.getId())
                    .eq(Employee::getStatus, 1));
        }
        if (request.employeeIds() == null || request.employeeIds().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择分配方式：企业整体或指定员工");
        }
        return loadEmployees(enterprise.getId(), request.employeeIds());
    }

    /** 逐员工校验：必须属于该企业且启用；跨企业一律 403。 */
    private List<Employee> loadEmployees(long enterpriseId, List<Long> employeeIds) {
        List<Long> ids = employeeIds.stream().distinct().toList();
        List<Employee> found = employeeMapper.selectList(Wrappers.<Employee>lambdaQuery()
                .in(Employee::getId, ids));
        if (found.size() != ids.size()) {
            throw new BizException(ErrorCode.NOT_FOUND, "存在不存在的员工");
        }
        for (Employee employee : found) {
            if (employee.getEnterpriseId() == null || employee.getEnterpriseId() != enterpriseId) {
                throw new BizException(ErrorCode.FORBIDDEN, "不能给其他企业的员工分配任务");
            }
            if (!Integer.valueOf(1).equals(employee.getStatus())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "员工「" + employee.getName() + "」已停用，不能分配");
            }
        }
        return found;
    }

    private long countAssignments(long taskId, String status) {
        var wrapper = Wrappers.<TrainingAssignment>lambdaQuery().eq(TrainingAssignment::getTaskId, taskId);
        if (status != null) {
            wrapper.eq(TrainingAssignment::getStatus, status);
        }
        Long count = assignmentMapper.selectCount(wrapper);
        return count == null ? 0 : count;
    }

    /** 业务时区当前时间；时间判定只信服务端。 */
    public LocalDateTime now() {
        return LocalDateTime.now(BUSINESS_ZONE);
    }
}