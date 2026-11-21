package com.insurance.training.archive.service;

import com.insurance.training.archive.vo.ExamRecordVo;
import com.insurance.training.archive.vo.LearningArchiveVo;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.exam.entity.ExamAttempt;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.mapper.ExamAttemptMapper;
import com.insurance.training.exam.mapper.ExamPaperMapper;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.learning.entity.LearningRecord;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 学员档案：汇总口径、只读不写、考试流水分页与最佳标记。 */
class ArchiveServiceTest {

    private final LearnerGuard learnerGuard = mock(LearnerGuard.class);
    private final LearningProgressService progressService = mock(LearningProgressService.class);
    private final TrainingAssignmentMapper assignmentMapper = mock(TrainingAssignmentMapper.class);
    private final TrainingTaskMapper taskMapper = mock(TrainingTaskMapper.class);
    private final PackageCourseMapper packageCourseMapper = mock(PackageCourseMapper.class);
    private final CourseVersionResourceMapper bindingMapper = mock(CourseVersionResourceMapper.class);
    private final CourseResourceMapper resourceMapper = mock(CourseResourceMapper.class);
    private final LearningRecordMapper recordMapper = mock(LearningRecordMapper.class);
    private final ResourceOpenLogMapper openLogMapper = mock(ResourceOpenLogMapper.class);
    private final ExamAttemptMapper attemptMapper = mock(ExamAttemptMapper.class);
    private final ExamPaperMapper paperMapper = mock(ExamPaperMapper.class);
    private final EnterpriseMapper enterpriseMapper = mock(EnterpriseMapper.class);

    private final ArchiveService service = new ArchiveService(learnerGuard, progressService,
            assignmentMapper, taskMapper, packageCourseMapper, bindingMapper, resourceMapper,
            recordMapper, openLogMapper, attemptMapper, paperMapper, enterpriseMapper);

    private final CurrentUser learnerUser = new CurrentUser(9L, PrincipalType.EMPLOYEE, null,
            DataScopeType.ENTERPRISE, 10L, 100L, "张三");

    private LearnerContext context() {
        Employee e = new Employee();
        e.setId(1L);
        e.setName("张三");
        e.setEnterpriseId(100L);
        e.setInsuranceCompanyId(10L);
        return new LearnerContext(9L, "13800000000", List.of(e), e);
    }

    private TrainingAssignment assignment(long id, long taskId, String status) {
        TrainingAssignment a = new TrainingAssignment();
        a.setId(id);
        a.setTaskId(taskId);
        a.setEmployeeId(1L);
        a.setPackageVersionId(900L);
        a.setStatus(status);
        return a;
    }

    private TrainingTask task(long id, String title) {
        TrainingTask t = new TrainingTask();
        t.setId(id);
        t.setTitle(title);
        t.setEnterpriseId(100L);
        t.setInsuranceCompanyId(10L);
        return t;
    }

    private ExamAttempt attempt(long id, long assignmentId, int sum, int total, int passed,
                                LocalDateTime submittedAt) {
        ExamAttempt a = new ExamAttempt();
        a.setId(id);
        a.setAssignmentId(assignmentId);
        a.setPaperId(7L);
        a.setStatus("SUBMITTED");
        a.setScoreSum(sum);
        a.setTotalScore(total);
        a.setPassScore(60);
        a.setPassed(passed);
        a.setSubmittedAt(submittedAt);
        return a;
    }

    private void stubEnterprise() {
        Enterprise ent = new Enterprise();
        ent.setId(100L);
        ent.setName("总公司");
        when(enterpriseMapper.selectList(any())).thenReturn(List.of(ent));
    }

    @Test
    @DisplayName("无任务时返回真实零值，不编造数据")
    void emptyArchive() {
        when(learnerGuard.requireLearner(any())).thenReturn(context());
        when(assignmentMapper.selectList(any())).thenReturn(List.of());

        LearningArchiveVo vo = service.learning(learnerUser);

        assertThat(vo.tasks()).isEmpty();
        assertThat(vo.summary().taskTotal()).isZero();
        assertThat(vo.summary().studyHours()).isZero();
        assertThat(vo.summary().examPassed()).isZero();
    }

    @Test
    @DisplayName("汇总：任务数/已完成/学时/考试通过 与造数一致，且不触发完成写回")
    void summaryMatchesFixtures() {
        when(learnerGuard.requireLearner(any())).thenReturn(context());
        when(assignmentMapper.selectList(any())).thenReturn(List.of(
                assignment(11L, 1L, "COMPLETED"),
                assignment(12L, 2L, "IN_PROGRESS")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, "入职培训"), task(2L, "合规复训")));
        stubEnterprise();

        PackageCourse pc1 = new PackageCourse();
        pc1.setPackageVersionId(900L);
        pc1.setCourseVersionId(500L);
        PackageCourse pc2 = new PackageCourse();
        pc2.setPackageVersionId(900L);
        pc2.setCourseVersionId(501L);
        when(packageCourseMapper.selectList(any())).thenReturn(List.of(pc1, pc2));

        CourseVersionResource b1 = new CourseVersionResource();
        b1.setCourseVersionId(500L);
        b1.setSectionId(700L);
        b1.setResourceId(800L);
        CourseVersionResource b2 = new CourseVersionResource();
        b2.setCourseVersionId(501L);
        b2.setSectionId(701L);
        b2.setResourceId(801L);
        when(bindingMapper.selectList(any())).thenReturn(List.of(b1, b2));

        CourseResource r1 = new CourseResource();
        r1.setId(800L);
        r1.setKind("VIDEO");
        CourseResource r2 = new CourseResource();
        r2.setId(801L);
        r2.setKind("VIDEO");
        when(resourceMapper.selectList(any())).thenReturn(List.of(r1, r2));

        // 章节判定：500 完成、501 未完成
        when(progressService.isSectionDone(anyList(), anyMap(), anySet(), anySet()))
                .thenAnswer(inv -> {
                    List<CourseVersionResource> binds = inv.getArgument(0);
                    return binds.get(0).getResourceId() == 800L;
                });

        LearningRecord done = new LearningRecord();
        done.setAssignmentId(11L);
        done.setResourceId(800L);
        done.setCompleted(1);
        done.setEffectiveSeconds(3600);
        LearningRecord partial = new LearningRecord();
        partial.setAssignmentId(12L);
        partial.setResourceId(801L);
        partial.setCompleted(0);
        partial.setEffectiveSeconds(1800);
        when(recordMapper.selectList(any())).thenReturn(List.of(done, partial));
        when(openLogMapper.selectList(any())).thenReturn(List.of());

        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt(1L, 11L, 90, 100, 1, LocalDateTime.of(2026, 11, 5, 10, 0))));
        when(paperMapper.selectList(any())).thenReturn(List.of());

        LearningArchiveVo vo = service.learning(learnerUser);

        assertThat(vo.summary().taskTotal()).isEqualTo(2);
        assertThat(vo.summary().taskCompleted()).isEqualTo(1);
        assertThat(vo.summary().courseTotal()).isEqualTo(4);
        assertThat(vo.summary().courseCompleted()).isEqualTo(2);
        assertThat(vo.summary().studyHours()).isEqualTo(1.5);
        assertThat(vo.summary().examPassed()).isEqualTo(1);
        assertThat(vo.tasks()).hasSize(2);
        // 新任务在前
        assertThat(vo.tasks().get(0).assignmentId()).isEqualTo(12L);
        assertThat(vo.tasks().get(1).bestExam()).isNotNull();
        assertThat(vo.tasks().get(1).bestExam().passed()).isTrue();
        assertThat(vo.tasks().get(0).enterpriseName()).isEqualTo("总公司");
        // 只读：绝不调用会写状态的 recalculate
        org.mockito.Mockito.verify(progressService, org.mockito.Mockito.never()).recalculate(anyLong());
    }

    @Test
    @DisplayName("考试流水：跨任务按交卷时间倒序，最佳成绩逐任务标记")
    void examRecordsOrderedAndBestMarked() {
        when(learnerGuard.requireLearner(any())).thenReturn(context());
        when(assignmentMapper.selectList(any())).thenReturn(List.of(
                assignment(11L, 1L, "COMPLETED"),
                assignment(12L, 2L, "IN_PROGRESS")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, "入职培训"), task(2L, "合规复训")));
        stubEnterprise();
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt(1L, 11L, 40, 100, 0, LocalDateTime.of(2026, 11, 1, 10, 0)),
                attempt(2L, 11L, 90, 100, 1, LocalDateTime.of(2026, 11, 5, 10, 0)),
                attempt(3L, 12L, 70, 100, 1, LocalDateTime.of(2026, 11, 3, 10, 0))));
        ExamPaper paper = new ExamPaper();
        paper.setId(7L);
        paper.setTitle("入职考试");
        when(paperMapper.selectList(any())).thenReturn(List.of(paper));

        PageResult<ExamRecordVo> page = service.exams(learnerUser, 1L, 10L);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(ExamRecordVo::attemptId).containsExactly(2L, 3L, 1L);
        assertThat(page.items().get(0).best()).isTrue();
        assertThat(page.items().get(0).taskTitle()).isEqualTo("入职培训");
        assertThat(page.items().get(1).best()).isTrue();
        assertThat(page.items().get(2).best()).isFalse();
        assertThat(page.items().get(0).paperTitle()).isEqualTo("入职考试");
    }

    @Test
    @DisplayName("考试流水分页：第二页返回剩余行")
    void examRecordsPaged() {
        when(learnerGuard.requireLearner(any())).thenReturn(context());
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment(11L, 1L, "COMPLETED")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, "入职培训")));
        stubEnterprise();
        when(attemptMapper.selectList(any())).thenReturn(List.of(
                attempt(1L, 11L, 40, 100, 0, LocalDateTime.of(2026, 11, 1, 10, 0)),
                attempt(2L, 11L, 90, 100, 1, LocalDateTime.of(2026, 11, 5, 10, 0)),
                attempt(3L, 11L, 50, 100, 0, LocalDateTime.of(2026, 11, 3, 10, 0))));
        when(paperMapper.selectList(any())).thenReturn(List.of());

        PageResult<ExamRecordVo> page2 = service.exams(learnerUser, 2L, 2L);

        assertThat(page2.total()).isEqualTo(3);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).attemptId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("无考试记录时返回真实空页")
    void noExamRecords() {
        when(learnerGuard.requireLearner(any())).thenReturn(context());
        when(assignmentMapper.selectList(any())).thenReturn(List.of(assignment(11L, 1L, "COMPLETED")));
        when(taskMapper.selectList(any())).thenReturn(List.of(task(1L, "入职培训")));
        stubEnterprise();
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        PageResult<ExamRecordVo> page = service.exams(learnerUser, 1L, 10L);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
