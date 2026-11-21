package com.insurance.training.report.service;

import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.employee.mapper.EmployeeMapper;
import com.insurance.training.exam.entity.ExamAttempt;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.learning.service.BusinessClock;
import com.insurance.training.report.vo.EnterpriseSummaryVo;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 报表聚合口径。
 *
 * <p>造数刻意使用「状态已完成但章节记录不全」的混合态：报表按 D 直接读 status 不重算完成度，
 * 本测试验的是聚合算术，不是完成判定本身（后者由 E2E 真实链路证明）。
 */
class ReportServiceTest {

    private final TrainingTaskMapper taskMapper = mock(TrainingTaskMapper.class);
    private final TrainingAssignmentMapper assignmentMapper = mock(TrainingAssignmentMapper.class);
    private final LearningRecordMapper learningRecordMapper = mock(LearningRecordMapper.class);
    private final ResourceOpenLogMapper openLogMapper = mock(ResourceOpenLogMapper.class);
    private final ExamAttemptMapper attemptMapper = mock(ExamAttemptMapper.class);
    private final ExamPaperMapper paperMapper = mock(ExamPaperMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final EnterpriseMapper enterpriseMapper = mock(EnterpriseMapper.class);
    private final InsuranceCompanyMapper companyMapper = mock(InsuranceCompanyMapper.class);
    private final PackageCourseMapper packageCourseMapper = mock(PackageCourseMapper.class);
    private final CourseVersionMapper courseVersionMapper = mock(CourseVersionMapper.class);
    private final CourseVersionResourceMapper bindingMapper = mock(CourseVersionResourceMapper.class);
    private final CourseSectionMapper sectionMapper = mock(CourseSectionMapper.class);
    private final CourseResourceMapper resourceMapper = mock(CourseResourceMapper.class);
    private final BusinessClock clock = mock(BusinessClock.class);

    private final ReportService service = new ReportService(taskMapper, assignmentMapper,
            learningRecordMapper, openLogMapper, attemptMapper, paperMapper, employeeMapper,
            enterpriseMapper, companyMapper, packageCourseMapper, courseVersionMapper, bindingMapper,
            sectionMapper, resourceMapper, clock, 200);

    private final CurrentUser superAdmin = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
            DataScopeType.ALL, null, null, "平台");
    private final CurrentUser unitAdmin = new CurrentUser(3L, PrincipalType.ADMIN, "ENTERPRISE_ADMIN",
            DataScopeType.ENTERPRISE, 10L, 100L, "子公司");

    private Enterprise enterprise(long id, long company) {
        Enterprise e = new Enterprise();
        e.setId(id);
        e.setInsuranceCompanyId(company);
        e.setName("子公司" + id);
        return e;
    }

    private TrainingTask task(long id, long company, long ent, LocalDateTime deadline) {
        TrainingTask t = new TrainingTask();
        t.setId(id);
        t.setInsuranceCompanyId(company);
        t.setEnterpriseId(ent);
        t.setPackageVersionId(900L);
        t.setTitle("任务" + id);
        t.setStatus("PUBLISHED");
        t.setLearnDeadlineAt(deadline);
        return t;
    }

    private TrainingAssignment assignment(long id, long taskId, long employeeId, String status) {
        TrainingAssignment a = new TrainingAssignment();
        a.setId(id);
        a.setTaskId(taskId);
        a.setEmployeeId(employeeId);
        a.setPackageVersionId(900L);
        a.setStatus(status);
        return a;
    }

    private ExamAttempt attempt(long id, long assignmentId, int sum, int total, int pass, int passed) {
        ExamAttempt a = new ExamAttempt();
        a.setId(id);
        a.setAssignmentId(assignmentId);
        a.setPaperId(7L);
        a.setStatus("SUBMITTED");
        a.setScoreSum(sum);
        a.setTotalScore(total);
        a.setPassScore(pass);
        a.setPassed(passed);
        a.setSubmittedAt(LocalDateTime.now());
        return a;
    }

    @Test
    @DisplayName("子公司汇总：应培/完成/完成率/有效学时/通过率/逾期 与手工期望一致")
    void enterpriseSummaryNumbers() {
        LocalDateTime past = LocalDateTime.now().minusDays(2);
        when(enterpriseMapper.selectList(any())).thenReturn(List.of(enterprise(100L, 10L)));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, 10L, 100L, past)));
        // 5 人：3 COMPLETED、2 IN_PROGRESS
        List<TrainingAssignment> assignments = List.of(
                assignment(11L, 1L, 1L, "COMPLETED"),
                assignment(12L, 1L, 2L, "COMPLETED"),
                assignment(13L, 1L, 3L, "COMPLETED"),
                assignment(14L, 1L, 4L, "IN_PROGRESS"),
                assignment(15L, 1L, 5L, "IN_PROGRESS"));
        when(assignmentMapper.selectList(any())).thenReturn(assignments);
        // 有效学时：3600 秒 = 1.0 小时
        when(learningRecordMapper.selectList(any())).thenReturn(List.of(
                record(11L, 3600), record(12L, 1800), record(13L, 1800)));
        // 3 人交卷，2 人通过（按人取最佳）
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt(1L, 11L, 90, 100, 60, 1),
                attempt(2L, 11L, 40, 100, 60, 0),
                attempt(3L, 12L, 70, 100, 60, 1),
                attempt(4L, 13L, 30, 100, 60, 0)));
        when(clock.now()).thenReturn(LocalDateTime.now());
        InsuranceCompany c = new InsuranceCompany();
        c.setId(10L);
        c.setName("集团");
        when(companyMapper.selectList(any())).thenReturn(List.of(c));

        PageResult<EnterpriseSummaryVo> page = service.enterpriseSummary(superAdmin, null, 1L, 10L);

        assertThat(page.total()).isEqualTo(1);
        EnterpriseSummaryVo vo = page.items().get(0);
        assertThat(vo.assignedCount()).isEqualTo(5);
        assertThat(vo.completedCount()).isEqualTo(3);
        assertThat(vo.completionRate()).isEqualTo(60.0);
        assertThat(vo.studyHours()).isEqualTo(2.0);
        assertThat(vo.examTakers()).isEqualTo(3);
        assertThat(vo.examPassed()).isEqualTo(2);
        assertThat(vo.examPassRate()).isEqualTo(66.7);
        // 逾期：2 人未完成且截止时间已过
        assertThat(vo.overdueCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("分母为 0 时比率为 0，不出现 NaN/除零")
    void zeroDenominatorYieldsZeroRate() {
        when(enterpriseMapper.selectList(any())).thenReturn(List.of(enterprise(100L, 10L)));
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());
        when(clock.now()).thenReturn(LocalDateTime.now());

        EnterpriseSummaryVo vo = service.enterpriseSummary(superAdmin, null, 1L, 10L).items().get(0);

        assertThat(vo.assignedCount()).isZero();
        assertThat(vo.completionRate()).isZero();
        assertThat(vo.examPassRate()).isZero();
        assertThat(vo.overdueCount()).isZero();
    }

    @Test
    @DisplayName("子公司管理员的范围条件被显式拼入查询")
    void unitAdminScopeIsExplicit() {
        when(enterpriseMapper.selectList(any())).thenReturn(List.of());
        when(clock.now()).thenReturn(LocalDateTime.now());

        PageResult<EnterpriseSummaryVo> page = service.enterpriseSummary(unitAdmin, null, 1L, 10L);

        // 范围过滤后无可见子公司 → 真实空页
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        org.mockito.Mockito.verify(enterpriseMapper).selectList(any());
    }

    @Test
    @DisplayName("任务汇总：下发/完成/通过率/逾期 与期望一致")
    void taskSummaryNumbers() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, 10L, 100L, past)));
        when(assignmentMapper.selectList(any())).thenReturn(List.of(
                assignment(11L, 1L, 1L, "COMPLETED"),
                assignment(12L, 1L, 2L, "IN_PROGRESS")));
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt(1L, 11L, 80, 100, 60, 1)));
        when(enterpriseMapper.selectList(any())).thenReturn(List.of(enterprise(100L, 10L)));
        when(clock.now()).thenReturn(LocalDateTime.now());

        PageResult<TaskSummaryVo> page = service.taskSummary(superAdmin, null, null, 1L, 10L);

        TaskSummaryVo vo = page.items().get(0);
        assertThat(vo.assignedCount()).isEqualTo(2);
        assertThat(vo.learningCompleted()).isEqualTo(1);
        assertThat(vo.completionRate()).isEqualTo(50.0);
        assertThat(vo.examTakers()).isEqualTo(1);
        assertThat(vo.examPassed()).isEqualTo(1);
        assertThat(vo.examPassRate()).isEqualTo(100.0);
        assertThat(vo.overdueCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("个人明细：章节完成、有效学时、考试历史全保留并标出最佳")
    void learnerDetail() {
        Employee e = new Employee();
        e.setId(1L);
        e.setName("张三");
        e.setPhone("13800001234");
        e.setInsuranceCompanyId(10L);
        e.setEnterpriseId(100L);
        when(employeeMapper.selectById(1L)).thenReturn(e);
        when(enterpriseMapper.selectById(100L)).thenReturn(enterprise(100L, 10L));
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment(11L, 1L, 1L, "COMPLETED")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, 10L, 100L, null)));

        PackageCourse pc = new PackageCourse();
        pc.setPackageVersionId(900L);
        pc.setCourseVersionId(500L);
        when(packageCourseMapper.selectList(any())).thenReturn(List.of(pc));

        CourseVersion cv = new CourseVersion();
        cv.setId(500L);
        cv.setTitle("课程A");
        when(courseVersionMapper.selectList(any())).thenReturn(List.of(cv));

        CourseVersionResource b1 = new CourseVersionResource();
        b1.setCourseVersionId(500L);
        b1.setSectionId(700L);
        b1.setResourceId(800L);
        when(bindingMapper.selectList(any())).thenReturn(List.of(b1));

        com.insurance.training.content.entity.CourseSection sec =
                new com.insurance.training.content.entity.CourseSection();
        sec.setId(700L);
        sec.setTitle("第一章");
        sec.setSortNo(1);
        when(sectionMapper.selectList(any())).thenReturn(List.of(sec));

        com.insurance.training.content.entity.CourseResource res =
                new com.insurance.training.content.entity.CourseResource();
        res.setId(800L);
        res.setDurationSeconds(600);
        when(resourceMapper.selectList(any())).thenReturn(List.of(res));

        when(learningRecordMapper.selectList(any())).thenReturn(List.of(record(11L, 600)));
        when(openLogMapper.selectList(any())).thenReturn(List.of());
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt(1L, 11L, 40, 100, 60, 0),
                attempt(2L, 11L, 90, 100, 60, 1)));
        com.insurance.training.exam.entity.ExamPaper paper = new com.insurance.training.exam.entity.ExamPaper();
        paper.setId(7L);
        paper.setTitle("试卷A");
        when(paperMapper.selectList(any())).thenReturn(List.of(paper));

        var vo = service.learner(superAdmin, 1L);

        assertThat(vo.employeeName()).isEqualTo("张三");
        assertThat(vo.phoneMasked()).isEqualTo("138****1234");
        assertThat(vo.tasks()).hasSize(1);
        var detail = vo.tasks().get(0);
        assertThat(detail.totalSeconds()).isEqualTo(600);
        assertThat(detail.attempts()).hasSize(2);
        assertThat(detail.attempts().stream().filter(a -> a.best()).count()).isEqualTo(1);
        assertThat(detail.attempts().stream().filter(a -> a.best()).findFirst().orElseThrow().scoreSum())
                .isEqualTo(90);
        var section = detail.courses().get(0).sections().get(0);
        assertThat(section.completed()).isTrue();
        assertThat(section.requiredSeconds()).isEqualTo(600);
        assertThat(section.effectiveSeconds()).isEqualTo(600);
    }

    private LearningRecord record(long assignmentId, int seconds) {
        LearningRecord r = new LearningRecord();
        r.setAssignmentId(assignmentId);
        r.setCourseVersionId(500L);
        r.setSectionId(700L);
        r.setResourceId(800L);
        r.setEffectiveSeconds(seconds);
        r.setCompleted(1);
        return r;
    }
}
