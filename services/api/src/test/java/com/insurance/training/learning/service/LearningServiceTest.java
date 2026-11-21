package com.insurance.training.learning.service;

import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
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
import com.insurance.training.employee.entity.Employee;
import com.insurance.training.learning.dto.HeartbeatRequest;
import com.insurance.training.learning.dto.HeartbeatResponse;
import com.insurance.training.learning.dto.OpenResourceRequest;
import com.insurance.training.learning.dto.OpenResourceResponse;
import com.insurance.training.learning.entity.LearningRecord;
import com.insurance.training.learning.entity.ResourceOpenLog;
import com.insurance.training.learning.mapper.LearningRecordMapper;
import com.insurance.training.learning.mapper.ResourceOpenLogMapper;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.entity.TrainingTask;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import com.insurance.training.training.mapper.TrainingTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 心跳与 open 的服务端判定。
 *
 * <p>核心语义（2026-11-10 修订）：视频**播完即完成**（ended 事件上报 finished），
 * 不再按时长累加判定。播放器已禁用拖动，想播完必须真的播完。
 */
class LearningServiceTest {

    private static final long EMPLOYEE_ID = 7L;
    private static final long ASSIGNMENT_ID = 30L;
    private static final long TASK_ID = 50L;
    private static final long PACKAGE_VERSION_ID = 12L;
    private static final long COURSE_VERSION_ID = 90L;
    private static final long SECTION_ID = 21L;
    private static final long VIDEO_ID = 31L;
    private static final long PDF_ID = 32L;

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 12, 1, 0, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 10, 0, 0);

    private static final CurrentUser LEARNER = new CurrentUser(EMPLOYEE_ID, PrincipalType.EMPLOYEE, null,
            DataScopeType.ENTERPRISE, 10L, 1L, "张三");

    private final TrainingAssignmentMapper assignmentMapper = Mockito.mock(TrainingAssignmentMapper.class);
    private final TrainingTaskMapper taskMapper = Mockito.mock(TrainingTaskMapper.class);
    private final LearningRecordMapper recordMapper = Mockito.mock(LearningRecordMapper.class);
    private final ResourceOpenLogMapper openLogMapper = Mockito.mock(ResourceOpenLogMapper.class);
    private final PackageCourseMapper packageCourseMapper = Mockito.mock(PackageCourseMapper.class);
    private final CourseVersionMapper courseVersionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseSectionMapper sectionMapper = Mockito.mock(CourseSectionMapper.class);
    private final CourseVersionResourceMapper bindingMapper = Mockito.mock(CourseVersionResourceMapper.class);
    private final CourseResourceMapper resourceMapper = Mockito.mock(CourseResourceMapper.class);

    private final BusinessClock clock = Mockito.mock(BusinessClock.class);
    private final LearnerGuard learnerGuard = Mockito.mock(LearnerGuard.class);
    private final LearningRateLimiter rateLimiter = Mockito.mock(LearningRateLimiter.class);
    private final LearningProgressService progressService = new LearningProgressService(
            assignmentMapper, packageCourseMapper, courseVersionMapper, courseMapper,
            sectionMapper, bindingMapper, resourceMapper, recordMapper, openLogMapper);
    private final LearningService service = new LearningService(
            assignmentMapper, taskMapper, courseVersionMapper, sectionMapper, bindingMapper,
            courseMapper, resourceMapper, recordMapper, openLogMapper,
            learnerGuard, progressService, rateLimiter, clock);

    @BeforeEach
    void setUp() {
        Mockito.when(clock.now()).thenReturn(NOW);
        Mockito.when(learnerGuard.requireLearner(Mockito.any())).thenReturn(learner());
        Mockito.when(assignmentMapper.selectById(ASSIGNMENT_ID))
                .thenReturn(assignment(EMPLOYEE_ID, "NOT_STARTED", null));
        Mockito.when(taskMapper.selectById(TASK_ID)).thenReturn(task("PUBLISHED"));
        Mockito.when(packageCourseMapper.selectList(Mockito.any())).thenReturn(List.of(link()));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version()));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course()));
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of(section()));
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of(
                binding(SECTION_ID, VIDEO_ID), binding(SECTION_ID, PDF_ID)));
        Mockito.when(resourceMapper.selectById(VIDEO_ID)).thenReturn(resource(VIDEO_ID, "VIDEO"));
        Mockito.when(resourceMapper.selectById(PDF_ID)).thenReturn(resource(PDF_ID, "PDF"));
        // 进度重算走 selectList（批量加载资源），与单条查询都要 stub
        Mockito.when(resourceMapper.selectList(Mockito.any()))
                .thenReturn(List.of(resource(VIDEO_ID, "VIDEO"), resource(PDF_ID, "PDF")));
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(openLogMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(assignmentMapper.advanceClockIfUnchanged(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(1);
    }

    // ---------- 归属与状态 ----------

    @Test
    @DisplayName("他人的 assignment → 403")
    void rejectsForeignAssignment() {
        Mockito.when(assignmentMapper.selectById(ASSIGNMENT_ID))
                .thenReturn(assignment(999L, "NOT_STARTED", null));

        assertThatThrownBy(() -> service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("任务已关闭 → 409")
    void rejectsClosedTask() {
        Mockito.when(taskMapper.selectById(TASK_ID)).thenReturn(task("CLOSED"));

        assertThatThrownBy(() -> service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("尚未开始（now < startAt）→ 409")
    void rejectsBeforeStart() {
        Mockito.when(clock.now()).thenReturn(START.minusSeconds(1));

        assertThatThrownBy(() -> service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("now == startAt 可学（左闭）")
    void allowsExactlyAtStart() {
        Mockito.when(clock.now()).thenReturn(START);

        HeartbeatResponse r = service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1");

        assertThat(r.finishedRecorded()).isFalse();
    }

    @Test
    @DisplayName("now == learnDeadlineAt 不可学（右开）→ 409")
    void rejectsExactlyAtDeadline() {
        Mockito.when(clock.now()).thenReturn(DEADLINE);

        assertThatThrownBy(() -> service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------- 资源校验 ----------

    @Test
    @DisplayName("resourceId 缺失 → 400")
    void rejectsMissingResourceId() {
        assertThatThrownBy(() -> service.heartbeat(LEARNER,
                new HeartbeatRequest(ASSIGNMENT_ID, SECTION_ID, null, null, null, null, null), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("心跳传 PDF 资源 → 400（应走 open）")
    void rejectsNonVideoResource() {
        assertThatThrownBy(() -> service.heartbeat(LEARNER, heartbeat(PDF_ID), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("open 传 VIDEO 资源 → 400（应走心跳）")
    void openRejectsVideoResource() {
        assertThatThrownBy(() -> service.open(LEARNER,
                new OpenResourceRequest(ASSIGNMENT_ID, VIDEO_ID), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    @DisplayName("章节不属于本任务 → 404")
    void rejectsForeignSection() {
        assertThatThrownBy(() -> service.heartbeat(LEARNER, heartbeat(VIDEO_ID, 999L), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------- 播放完成 ----------

    @Test
    @DisplayName("未上报 finished → 不标记完成")
    void doesNotMarkCompletedWithoutFinished() {
        service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1");

        Mockito.verify(recordMapper, Mockito.never())
                .markCompleted(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    @DisplayName("上报 finished=true → 标记该视频完成")
    void marksCompletedOnFinished() {
        HeartbeatResponse r = service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");

        Mockito.verify(recordMapper).markCompleted(ASSIGNMENT_ID, COURSE_VERSION_ID, SECTION_ID, VIDEO_ID);
        assertThat(r.finishedRecorded()).isTrue();
    }

    @Test
    @DisplayName("声称播完但服务端累计时长不足 → 不计完成（防抓包重放刷完成）")
    void refusesFinishedWhenWatchedLessThanDuration() {
        // 视频 600 秒；服务端只累计了 3 秒，客户端却声称播完
        Mockito.when(resourceMapper.selectById(VIDEO_ID))
                .thenReturn(resourceWithDuration(VIDEO_ID, "VIDEO", 600));
        Mockito.when(recordMapper.selectOne(Mockito.any())).thenReturn(recordOf(3));

        HeartbeatResponse r = service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");

        assertThat(r.finishedRecorded()).isFalse();
        Mockito.verify(recordMapper, Mockito.never())
                .markCompleted(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    @DisplayName("累计时长达到视频时长后声称播完 → 计完成")
    void acceptsFinishedWhenWatchedReachesDuration() {
        Mockito.when(resourceMapper.selectById(VIDEO_ID))
                .thenReturn(resourceWithDuration(VIDEO_ID, "VIDEO", 600));
        Mockito.when(recordMapper.selectOne(Mockito.any())).thenReturn(recordOf(600));

        HeartbeatResponse r = service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");

        assertThat(r.finishedRecorded()).isTrue();
    }

    @Test
    @DisplayName("视频时长未知时声称播完 → 按客户端处理并标记 unverified")
    void marksUnverifiedWhenDurationUnknown() {
        Mockito.when(resourceMapper.selectById(VIDEO_ID))
                .thenReturn(resourceWithDuration(VIDEO_ID, "VIDEO", null));

        HeartbeatResponse r = service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");

        assertThat(r.finishedRecorded()).isTrue();
        assertThat(r.durationUnverified()).isTrue();
    }

    @Test
    @DisplayName("重复上报 finished → 幂等，不影响结果")
    void finishedIsIdempotent() {
        service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");
        service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");

        Mockito.verify(recordMapper, Mockito.times(2))
                .markCompleted(ASSIGNMENT_ID, COURSE_VERSION_ID, SECTION_ID, VIDEO_ID);
    }

    @Test
    @DisplayName("章节内所有视频都播完 → 章节完成（任务完成）")
    void sectionAndTaskCompleteWhenAllVideosFinished() {
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of(doneRecord(VIDEO_ID)));
        Mockito.when(assignmentMapper.completeIfNotCompleted(Mockito.anyLong(), Mockito.any())).thenReturn(1);

        HeartbeatResponse r = service.heartbeat(LEARNER, finishedHeartbeat(VIDEO_ID), "127.0.0.1");

        assertThat(r.completed()).isTrue();
    }

    @Test
    @DisplayName("未播完 → 任务未完成")
    void taskNotCompletedBeforeFinish() {
        HeartbeatResponse r = service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1");

        assertThat(r.completed()).isFalse();
    }

    // ---------- 状态推进 ----------

    @Test
    @DisplayName("首次心跳把任务推进为进行中")
    void marksInProgressOnFirstHeartbeat() {
        service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1");

        Mockito.verify(assignmentMapper).markInProgress(Mockito.eq(ASSIGNMENT_ID), Mockito.any());
    }

    @Test
    @DisplayName("时钟 CAS 失败（并发心跳）→ 不重复推进状态")
    void concurrentHeartbeatDoesNotAdvanceStatus() {
        Mockito.when(assignmentMapper.advanceClockIfUnchanged(Mockito.anyLong(), Mockito.any(), Mockito.any()))
                .thenReturn(0);

        service.heartbeat(LEARNER, heartbeat(VIDEO_ID), "127.0.0.1");

        Mockito.verify(assignmentMapper, Mockito.never())
                .markInProgress(Mockito.anyLong(), Mockito.any());
    }

    // ---------- 非视频 open ----------

    @Test
    @DisplayName("open 幂等：重复登记返回 firstOpen=false")
    void openIsIdempotent() {
        Mockito.when(openLogMapper.insert(Mockito.any(ResourceOpenLog.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        OpenResourceResponse r = service.open(LEARNER,
                new OpenResourceRequest(ASSIGNMENT_ID, PDF_ID), "127.0.0.1");

        assertThat(r.firstOpen()).isFalse();
    }

    @Test
    @DisplayName("首次 open → firstOpen=true 并推进任务状态")
    void firstOpenMarksInProgress() {
        Mockito.when(openLogMapper.insert(Mockito.any(ResourceOpenLog.class))).thenReturn(1);

        OpenResourceResponse r = service.open(LEARNER,
                new OpenResourceRequest(ASSIGNMENT_ID, PDF_ID), "127.0.0.1");

        assertThat(r.firstOpen()).isTrue();
        Mockito.verify(assignmentMapper).markInProgress(Mockito.eq(ASSIGNMENT_ID), Mockito.any());
    }

    // ---------- fixtures ----------

    private static LearnerContext learner() {
        Employee primary = employee();
        return new LearnerContext(1L, primary.getPhone(), List.of(primary), primary);
    }

    private static Employee employee() {
        Employee e = new Employee();
        e.setId(EMPLOYEE_ID);
        e.setEnterpriseId(1L);
        e.setInsuranceCompanyId(10L);
        e.setPhone("13800138000");
        e.setStatus(1);
        return e;
    }

    private static HeartbeatRequest heartbeat(long resourceId) {
        return new HeartbeatRequest(ASSIGNMENT_ID, SECTION_ID, resourceId, 0L, 0L, null, false);
    }

    private static HeartbeatRequest heartbeat(long resourceId, long sectionId) {
        return new HeartbeatRequest(ASSIGNMENT_ID, sectionId, resourceId, 0L, 0L, null, false);
    }

    private static HeartbeatRequest finishedHeartbeat(long resourceId) {
        return new HeartbeatRequest(ASSIGNMENT_ID, SECTION_ID, resourceId, null, null, null, true);
    }

    private static LearningRecord doneRecord(long resourceId) {
        LearningRecord r = new LearningRecord();
        r.setId(resourceId);
        r.setAssignmentId(ASSIGNMENT_ID);
        r.setCourseVersionId(COURSE_VERSION_ID);
        r.setSectionId(SECTION_ID);
        r.setResourceId(resourceId);
        r.setCompleted(1);
        r.setEffectiveSeconds(0);
        return r;
    }

    private static TrainingAssignment assignment(long employeeId, String status, LocalDateTime clock) {
        TrainingAssignment a = new TrainingAssignment();
        a.setId(ASSIGNMENT_ID);
        a.setTaskId(TASK_ID);
        a.setEmployeeId(employeeId);
        a.setPackageVersionId(PACKAGE_VERSION_ID);
        a.setStatus(status);
        a.setLastHeartbeatAt(clock);
        return a;
    }

    private static TrainingTask task(String status) {
        TrainingTask t = new TrainingTask();
        t.setId(TASK_ID);
        t.setStatus(status);
        t.setStartAt(START);
        t.setLearnDeadlineAt(DEADLINE);
        t.setPackageVersionId(PACKAGE_VERSION_ID);
        return t;
    }

    private static PackageCourse link() {
        PackageCourse c = new PackageCourse();
        c.setPackageVersionId(PACKAGE_VERSION_ID);
        c.setCourseVersionId(COURSE_VERSION_ID);
        c.setSortNo(1);
        return c;
    }

    private static CourseVersion version() {
        CourseVersion v = new CourseVersion();
        v.setId(COURSE_VERSION_ID);
        v.setCourseId(5L);
        v.setTitle("课程");
        return v;
    }

    private static Course course() {
        Course c = new Course();
        c.setId(5L);
        c.setTitle("课程");
        return c;
    }

    private static CourseSection section() {
        CourseSection s = new CourseSection();
        s.setId(SECTION_ID);
        s.setCourseVersionId(COURSE_VERSION_ID);
        s.setTitle("第一章");
        s.setSortNo(1);
        return s;
    }

    private static CourseVersionResource binding(long sectionId, long resourceId) {
        CourseVersionResource b = new CourseVersionResource();
        b.setCourseVersionId(COURSE_VERSION_ID);
        b.setSectionId(sectionId);
        b.setResourceId(resourceId);
        b.setSortNo(1);
        return b;
    }

    private static CourseResource resourceWithDuration(long id, String kind, Integer duration) {
        CourseResource r = resource(id, kind);
        r.setDurationSeconds(duration);
        return r;
    }

    private static LearningRecord recordOf(int seconds) {
        LearningRecord r = new LearningRecord();
        r.setId(1L);
        r.setAssignmentId(ASSIGNMENT_ID);
        r.setCourseVersionId(COURSE_VERSION_ID);
        r.setSectionId(SECTION_ID);
        r.setResourceId(VIDEO_ID);
        r.setEffectiveSeconds(seconds);
        r.setCompleted(0);
        return r;
    }

    private static CourseResource resource(long id, String kind) {
        CourseResource r = new CourseResource();
        r.setId(id);
        r.setCourseId(5L);
        r.setInsuranceCompanyId(10L);
        r.setKind(kind);
        r.setObjectKey("k/" + id);
        r.setOriginalName("f" + id);
        r.setStatus("ACTIVE");
        return r;
    }
}
