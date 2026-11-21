package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
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
import com.insurance.training.training.vo.TaskVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 建任务的准入校验：只接受已发布、同公司、未含下线课程的培训包版本。 */
class TaskServiceTest {

    private static final String IP = "127.0.0.1";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 12, 1, 0, 0);

    private static final CurrentUser COMPANY_ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    private final TrainingTaskMapper taskMapper = Mockito.mock(TrainingTaskMapper.class);
    private final TrainingPackageVersionMapper versionMapper = Mockito.mock(TrainingPackageVersionMapper.class);
    private final TrainingPackageMapper packageMapper = Mockito.mock(TrainingPackageMapper.class);
    private final PackageCourseMapper packageCourseMapper = Mockito.mock(PackageCourseMapper.class);
    private final CourseVersionMapper courseVersionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final TrainingAssignmentMapper assignmentMapper = Mockito.mock(TrainingAssignmentMapper.class);
    private final EmployeeMapper employeeMapper = Mockito.mock(EmployeeMapper.class);
    private final EnterpriseService enterpriseService = Mockito.mock(EnterpriseService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final com.insurance.training.learning.mapper.LearningRecordMapper learningRecordMapper =
            Mockito.mock(com.insurance.training.learning.mapper.LearningRecordMapper.class);

    private final AssignmentService assignmentService =
            new AssignmentService(assignmentMapper, auditService);
    private final TaskService service = new TaskService(taskMapper, versionMapper, packageMapper,
            packageCourseMapper, courseVersionMapper, courseMapper, enterpriseService,
            employeeMapper, assignmentMapper, learningRecordMapper, assignmentService, auditService, 200);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingTask.class);
        TableInfoHelper.initTableInfo(assistant, TrainingAssignment.class);
        TableInfoHelper.initTableInfo(assistant, TrainingPackageVersion.class);
        TableInfoHelper.initTableInfo(assistant, TrainingPackage.class);
        TableInfoHelper.initTableInfo(assistant, PackageCourse.class);
    }

    @BeforeEach
    void bindContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        CurrentUserHolder.set(COMPANY_ADMIN);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- 时间与准入 ----------

    @Test
    @DisplayName("startAt >= learnDeadlineAt → 400，且不写任何表")
    void rejectsInvertedTimeWindow() {
        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", DEADLINE, START, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        Mockito.verifyNoInteractions(taskMapper, versionMapper);
    }

    @Test
    @DisplayName("startAt == learnDeadlineAt 也算倒挂（零长度窗口）→ 400")
    void rejectsZeroLengthWindow() {
        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, START, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("培训包版本不存在 → 404")
    void rejectsMissingPackageVersion() {
        givenEnterprise(1L, 10L);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("培训包版本未发布（DRAFT）→ 409")
    void rejectsDraftPackageVersion() {
        givenEnterprise(1L, 10L);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(packageVersion("DRAFT"));

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(taskMapper, Mockito.never()).insert(Mockito.any(TrainingTask.class));
    }

    @Test
    @DisplayName("培训包已下线 → 409（下线后不可作为新任务选包）")
    void rejectsOfflinePackage() {
        givenEnterprise(1L, 10L);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(packageVersion("PUBLISHED"));
        Mockito.when(packageMapper.selectById(1L)).thenReturn(pkg("OFFLINE"));

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("培训包与目标子公司不属于同一集团 → 403")
    void rejectsCrossCompanyPackage() {
        givenEnterprise(1L, 10L);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(packageVersion("PUBLISHED"));
        Mockito.when(packageMapper.selectById(1L)).thenReturn(pkg("PUBLISHED", 99L));

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("培训包内课程已下线 → 409，提示重新制包")
    void rejectsPackageContainingOfflineCourse() {
        givenPublishablePackage();
        Mockito.when(courseMapper.selectList(Mockito.<Wrapper<Course>>any()))
                .thenReturn(List.of(course(5L, "OFFLINE")));

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(taskMapper, Mockito.never()).insert(Mockito.any(TrainingTask.class));
    }

    // ---------- 建任务成功 ----------

    @Test
    @DisplayName("建任务即发布，且 assignment 的 last_heartbeat_at 必须为 NULL")
    void createsPublishedTaskWithoutPrefillingClock() {
        givenPublishablePackage();
        givenEmployees(employee(101L, 1L, 1), employee(102L, 1L, 1));
        Mockito.when(taskMapper.insert(Mockito.any(TrainingTask.class))).thenAnswer(inv -> {
            TrainingTask t = inv.getArgument(0);
            t.setId(50L);
            return 1;
        });
        Mockito.when(assignmentMapper.insertIfTaskOpen(Mockito.any(TrainingAssignment.class))).thenReturn(1);

        TaskVo vo = service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, " 新员工培训 ", START, DEADLINE, true, null), IP);

        assertThat(vo.status()).isEqualTo("PUBLISHED");
        assertThat(vo.title()).isEqualTo("新员工培训");
        ArgumentCaptor<TrainingTask> taskCaptor = ArgumentCaptor.forClass(TrainingTask.class);
        Mockito.verify(taskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getInsuranceCompanyId()).isEqualTo(10L);
        assertThat(taskCaptor.getValue().getEnterpriseId()).isEqualTo(1L);
        assertThat(taskCaptor.getValue().getPackageVersionId()).isEqualTo(11L);
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("PUBLISHED");

        ArgumentCaptor<TrainingAssignment> aCaptor = ArgumentCaptor.forClass(TrainingAssignment.class);
        Mockito.verify(assignmentMapper, Mockito.times(2)).insertIfTaskOpen(aCaptor.capture());
        assertThat(aCaptor.getAllValues()).extracting(TrainingAssignment::getEmployeeId)
                .containsExactlyInAnyOrder(101L, 102L);
        assertThat(aCaptor.getAllValues()).allSatisfy(a -> {
            assertThat(a.getStatus()).isEqualTo("NOT_STARTED");
            assertThat(a.getStartedAt()).isNull();
            assertThat(a.getCompletedAt()).isNull();
            assertThat(a.getLastHeartbeatAt()).isNull();
        });
    }

    @Test
    @DisplayName("企业没有可用员工时明确失败，不建出零分配的任务")
    void rejectsWhenNoEmployeeAvailable() {
        givenPublishablePackage();
        Mockito.when(employeeMapper.selectList(Mockito.<Wrapper<Employee>>any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, true, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        Mockito.verify(taskMapper, Mockito.never()).insert(Mockito.any(TrainingTask.class));
    }

    @Test
    @DisplayName("指定他企业员工 → 403，且不写任何 assignment")
    void rejectsEmployeeOfAnotherEnterprise() {
        givenPublishablePackage();
        Mockito.when(employeeMapper.selectList(Mockito.<Wrapper<Employee>>any()))
                .thenReturn(List.of(employee(201L, 2L, 1)));

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, false, List.of(201L)), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verify(assignmentMapper, Mockito.never()).insertIfTaskOpen(Mockito.any());
    }

    @Test
    @DisplayName("指定停用员工 → 400")
    void rejectsDisabledEmployee() {
        givenPublishablePackage();
        Mockito.when(employeeMapper.selectList(Mockito.<Wrapper<Employee>>any()))
                .thenReturn(List.of(employee(101L, 1L, 0)));

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, false, List.of(101L)), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("assignAll=false 且未提供员工 → 400")
    void rejectsMissingEmployeeSelection() {
        givenPublishablePackage();

        assertThatThrownBy(() -> service.create(COMPANY_ADMIN,
                new TaskRequest(1L, 11L, "任务", START, DEADLINE, false, List.of()), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    // ---------- 关闭 ----------

    @Test
    @DisplayName("关闭任务走状态 CAS（WHERE status=PUBLISHED）")
    void closeUsesConditionalUpdate() {
        givenTask("PUBLISHED");
        Mockito.when(taskMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingTask>>any())).thenReturn(1);

        TaskVo vo = service.close(COMPANY_ADMIN, 50L, IP);

        assertThat(vo.status()).isEqualTo("CLOSED");
        LambdaUpdateWrapper<TrainingTask> w = captureTaskUpdate();
        assertThat(w.getSqlSet()).contains("status");
        assertThat(w.getParamNameValuePairs()).containsValue("PUBLISHED");
    }

    @Test
    @DisplayName("重复关闭 → 409")
    void closeRejectsAlreadyClosed() {
        givenTask("CLOSED");

        assertThatThrownBy(() -> service.close(COMPANY_ADMIN, 50L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(taskMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<TrainingTask>>any());
    }

    @Test
    @DisplayName("关闭时 CAS 0 行（被并发关闭）→ 409")
    void closeConflictsWhenCasLoses() {
        givenTask("PUBLISHED");
        Mockito.when(taskMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingTask>>any())).thenReturn(0);

        assertThatThrownBy(() -> service.close(COMPANY_ADMIN, 50L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------- 列表范围与进度 ----------

    @Test
    @DisplayName("企业管理员列表 SQL 同时带公司与企业过滤")
    void pageFiltersByCompanyAndEnterpriseForEnterpriseAdmin() {
        CurrentUser enterpriseAdmin = new CurrentUser(2L, PrincipalType.ADMIN, "ENTERPRISE_ADMIN",
                DataScopeType.ENTERPRISE, 10L, 100L, "企业管理员");
        CurrentUserHolder.set(enterpriseAdmin);
        Page<TrainingTask> page = new Page<>(1, 10);
        page.setRecords(List.of(task(50L, "PUBLISHED")));
        page.setTotal(1);
        Mockito.doReturn(page).when(taskMapper).selectPage(Mockito.any(), Mockito.any());

        service.page(enterpriseAdmin, null, null, null, 1L, 10L);

        LambdaQueryWrapper<TrainingTask> w = capturePageQuery();
        assertThat(w.getSqlSegment()).contains("insurance_company_id").contains("enterprise_id");
        assertThat(w.getParamNameValuePairs()).containsValue(10L).containsValue(100L);
    }

    @Test
    @DisplayName("进度统计：总数/已完成/进行中/未开始")
    void progressCountsByStatus() {
        givenTask("PUBLISHED");
        Mockito.when(assignmentMapper.selectCount(Mockito.<Wrapper<TrainingAssignment>>any()))
                .thenReturn(5L, 2L, 1L, 2L);

        var p = service.progress(COMPANY_ADMIN, 50L);

        assertThat(p.total()).isEqualTo(5);
        assertThat(p.completed()).isEqualTo(2);
        assertThat(p.inProgress()).isEqualTo(1);
        assertThat(p.notStarted()).isEqualTo(2);
    }


    @Test
    @DisplayName("逐人分配明细：按员工聚合，带状态与累计时长")
    void assignmentRowsCarryEmployeeAndSeconds() {
        givenTask("PUBLISHED");
        TrainingAssignment a1 = assignmentRow(1L, 101L, "COMPLETED", 600);
        TrainingAssignment a2 = assignmentRow(2L, 102L, "IN_PROGRESS", 120);
        Mockito.when(assignmentMapper.selectList(Mockito.<Wrapper<TrainingAssignment>>any()))
                .thenReturn(List.of(a1, a2));
        Mockito.when(employeeMapper.selectList(Mockito.<Wrapper<Employee>>any()))
                .thenReturn(List.of(employeeNamed(101L, "张三"), employeeNamed(102L, "王五")));
        Mockito.when(learningRecordMapper.selectList(Mockito.<Wrapper<com.insurance.training.learning.entity.LearningRecord>>any()))
                .thenReturn(List.of(record(1L, 300), record(1L, 300), record(2L, 120)));

        List<TaskAssignmentVo> rows = service.assignmentRows(COMPANY_ADMIN, 50L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("张三");
        assertThat(rows.get(0).status()).isEqualTo("COMPLETED");
        assertThat(rows.get(0).effectiveSeconds()).isEqualTo(600);
        assertThat(rows.get(1).name()).isEqualTo("王五");
        assertThat(rows.get(1).effectiveSeconds()).isEqualTo(120);
    }

    @Test
    @DisplayName("逐人明细：跨公司访问任务 → 403")
    void assignmentRowsRejectCrossCompany() {
        TrainingTask other = task(50L, "PUBLISHED");
        other.setInsuranceCompanyId(99L);
        Mockito.when(taskMapper.selectById(50L)).thenReturn(other);

        assertThatThrownBy(() -> service.assignmentRows(COMPANY_ADMIN, 50L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verifyNoInteractions(learningRecordMapper);
    }

    // ---------- fixtures ----------

    private static TrainingAssignment assignmentRow(long id, long employeeId, String status, int seconds) {
        TrainingAssignment a = new TrainingAssignment();
        a.setId(id);
        a.setTaskId(50L);
        a.setEmployeeId(employeeId);
        a.setPackageVersionId(11L);
        a.setStatus(status);
        a.setStartedAt(START);
        a.setCompletedAt("COMPLETED".equals(status) ? START.plusDays(1) : null);
        return a;
    }

    private static Employee employeeNamed(long id, String name) {
        Employee e = employee(id, 1L, 1);
        e.setName(name);
        return e;
    }

    private static com.insurance.training.learning.entity.LearningRecord record(long assignmentId, int seconds) {
        com.insurance.training.learning.entity.LearningRecord r = new com.insurance.training.learning.entity.LearningRecord();
        r.setId(assignmentId * 1000 + seconds);
        r.setAssignmentId(assignmentId);
        r.setCourseVersionId(90L);
        r.setSectionId(21L);
        r.setResourceId(31L);
        r.setEffectiveSeconds(seconds);
        return r;
    }


    private void givenEnterprise(long id, long companyId) {
        Enterprise e = new Enterprise();
        e.setId(id);
        e.setInsuranceCompanyId(companyId);
        e.setStatus(1);
        Mockito.when(enterpriseService.getVisible(Mockito.any(), Mockito.eq(id))).thenReturn(e);
    }

    private void givenPublishablePackage() {
        givenEnterprise(1L, 10L);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(packageVersion("PUBLISHED"));
        Mockito.when(packageMapper.selectById(1L)).thenReturn(pkg("PUBLISHED"));

        PackageCourse link = new PackageCourse();
        link.setId(70L);
        link.setPackageVersionId(11L);
        link.setCourseVersionId(90L);
        link.setSortNo(1);
        Mockito.when(packageCourseMapper.selectList(Mockito.<Wrapper<PackageCourse>>any()))
                .thenReturn(List.of(link));

        CourseVersion cv = new CourseVersion();
        cv.setId(90L);
        cv.setCourseId(5L);
        cv.setTitle("课程版本");
        cv.setStatus("PUBLISHED");
        Mockito.when(courseVersionMapper.selectList(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(List.of(cv));

        Mockito.when(courseMapper.selectList(Mockito.<Wrapper<Course>>any()))
                .thenReturn(List.of(course(5L, "PUBLISHED")));
    }

    private void givenEmployees(Employee... employees) {
        Mockito.when(employeeMapper.selectList(Mockito.<Wrapper<Employee>>any()))
                .thenReturn(List.of(employees));
    }

    private void givenTask(String status) {
        TrainingTask t = task(50L, status);
        Mockito.when(taskMapper.selectById(50L)).thenReturn(t);
    }

    private static TrainingPackageVersion packageVersion(String status) {
        TrainingPackageVersion v = new TrainingPackageVersion();
        v.setId(11L);
        v.setPackageId(1L);
        v.setVersionNo(1);
        v.setTitle("包版本");
        v.setStatus(status);
        return v;
    }

    private static TrainingPackage pkg(String status) {
        return pkg(status, 10L);
    }

    private static TrainingPackage pkg(String status, long companyId) {
        TrainingPackage p = new TrainingPackage();
        p.setId(1L);
        p.setInsuranceCompanyId(companyId);
        p.setTitle("培训包");
        p.setStatus(status);
        p.setLatestVersionNo(1);
        p.setPublishedVersionNo(1);
        return p;
    }

    private static Course course(long id, String status) {
        Course c = new Course();
        c.setId(id);
        c.setInsuranceCompanyId(10L);
        c.setTitle("课程" + id);
        c.setStatus(status);
        return c;
    }

    private static Employee employee(long id, long enterpriseId, int status) {
        Employee e = new Employee();
        e.setId(id);
        e.setEnterpriseId(enterpriseId);
        e.setInsuranceCompanyId(10L);
        e.setName("员工" + id);
        e.setStatus(status);
        return e;
    }

    private static TrainingTask task(long id, String status) {
        TrainingTask t = new TrainingTask();
        t.setId(id);
        t.setInsuranceCompanyId(10L);
        t.setEnterpriseId(1L);
        t.setPackageVersionId(11L);
        t.setTitle("任务");
        t.setStartAt(START);
        t.setLearnDeadlineAt(DEADLINE);
        t.setStatus(status);
        return t;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<TrainingTask> captureTaskUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<TrainingTask>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(taskMapper).update(Mockito.isNull(), captor.capture());
        LambdaUpdateWrapper<TrainingTask> w = captor.getValue();
        w.getSqlSegment();
        return w;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<TrainingTask> capturePageQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TrainingTask>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(taskMapper).selectPage(Mockito.any(), captor.capture());
        LambdaQueryWrapper<TrainingTask> w = captor.getValue();
        w.getSqlSegment();
        return w;
    }
}