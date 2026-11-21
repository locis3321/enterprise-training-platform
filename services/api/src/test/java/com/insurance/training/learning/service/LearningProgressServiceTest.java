package com.insurance.training.learning.service;

import com.insurance.training.common.exception.BizException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 完成判定：章节/课程/任务三级，纯资料章节看打开情况，COMPLETED 只进不退。 */
class LearningProgressServiceTest {

    private static final long ASSIGNMENT_ID = 30L;
    private static final long PACKAGE_VERSION_ID = 12L;
    private static final long CV1 = 90L;
    private static final long CV2 = 91L;
    private static final long COURSE_1 = 5L;
    private static final long COURSE_2 = 6L;

    private final TrainingAssignmentMapper assignmentMapper = Mockito.mock(TrainingAssignmentMapper.class);
    private final PackageCourseMapper packageCourseMapper = Mockito.mock(PackageCourseMapper.class);
    private final CourseVersionMapper courseVersionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseSectionMapper sectionMapper = Mockito.mock(CourseSectionMapper.class);
    private final CourseVersionResourceMapper bindingMapper = Mockito.mock(CourseVersionResourceMapper.class);
    private final CourseResourceMapper resourceMapper = Mockito.mock(CourseResourceMapper.class);
    private final LearningRecordMapper recordMapper = Mockito.mock(LearningRecordMapper.class);
    private final ResourceOpenLogMapper openLogMapper = Mockito.mock(ResourceOpenLogMapper.class);

    private final LearningProgressService service = new LearningProgressService(
            assignmentMapper, packageCourseMapper, courseVersionMapper, courseMapper,
            sectionMapper, bindingMapper, resourceMapper, recordMapper, openLogMapper);

    @BeforeEach
    void setUp() {
        Mockito.when(assignmentMapper.selectById(ASSIGNMENT_ID))
                .thenReturn(assignment("IN_PROGRESS"));
        Mockito.when(packageCourseMapper.selectList(Mockito.any()))
                .thenReturn(List.of(link(CV1, 1), link(CV2, 2)));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version(CV1, COURSE_1), version(CV2, COURSE_2)));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course(COURSE_1), course(COURSE_2)));
        // 一个课程版本一个章节，时长各 600
        Mockito.when(sectionMapper.selectList(Mockito.any()))
                .thenReturn(List.of(section(21L, CV1, 600), section(22L, CV2, 600)));
        Mockito.when(bindingMapper.selectList(Mockito.any()))
                .thenReturn(List.of(binding(CV1, 21L, 31L, "VIDEO"), binding(CV2, 22L, 32L, "VIDEO")));
        Mockito.when(resourceMapper.selectList(Mockito.any()))
                .thenReturn(List.of(resource(31L, "VIDEO"), resource(32L, "VIDEO")));
        Mockito.when(openLogMapper.selectList(Mockito.any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("一章未完成 → 任务未完成，章节完成数 1/2")
    void notCompletedWhenSectionsIncomplete() {
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of(
                done(21L, 31L)));

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.completed()).isFalse();
        assertThat(p.sectionDone()).isEqualTo(1);
        assertThat(p.sectionTotal()).isEqualTo(2);
    }

    @Test
    @DisplayName("两章的视频都播完 → 任务完成")
    void completedWhenAllVideosFinished() {
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of(
                done(21L, 31L), done(22L, 32L)));
        Mockito.when(assignmentMapper.completeIfNotCompleted(Mockito.anyLong(), Mockito.any())).thenReturn(1);

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.completed()).isTrue();
    }

    @Test
    @DisplayName("同一章节两个视频都播完 → 章节完成")
    void sectionCompletesWhenAllVideosFinished() {
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of(section(21L, CV1, 600)));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version(CV1, COURSE_1)));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course(COURSE_1)));
        Mockito.when(packageCourseMapper.selectList(Mockito.any())).thenReturn(List.of(link(CV1, 1)));
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of(
                binding(CV1, 21L, 31L, "VIDEO"), binding(CV1, 21L, 32L, "VIDEO")));
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of(
                done(21L, 31L), done(21L, 32L)));

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.sectionDone()).isEqualTo(1);
        assertThat(p.sectionTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一章节只播完一个视频 → 章节未完成")
    void sectionNotDoneWhenOnlyOneVideoFinished() {
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of(section(21L, CV1, 600)));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version(CV1, COURSE_1)));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course(COURSE_1)));
        Mockito.when(packageCourseMapper.selectList(Mockito.any())).thenReturn(List.of(link(CV1, 1)));
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of(
                binding(CV1, 21L, 31L, "VIDEO"), binding(CV1, 21L, 32L, "VIDEO")));
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of(done(21L, 31L)));

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.sectionDone()).isZero();
    }

    @Test
    @DisplayName("纯资料章节：所有资源都打开才算完成")
    void pureDocumentSectionRequiresAllOpened() {
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of(section(21L, CV1, 0)));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version(CV1, COURSE_1)));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course(COURSE_1)));
        Mockito.when(packageCourseMapper.selectList(Mockito.any())).thenReturn(List.of(link(CV1, 1)));
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of(
                binding(CV1, 21L, 31L, "PDF"), binding(CV1, 21L, 32L, "PPTX")));
        Mockito.when(resourceMapper.selectList(Mockito.any()))
                .thenReturn(List.of(resource(31L, "PDF"), resource(32L, "PPTX")));
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(openLogMapper.selectList(Mockito.any()))
                .thenReturn(List.of(open(31L), open(32L)));

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.sectionDone()).isEqualTo(1);
    }

    @Test
    @DisplayName("纯资料章节只打开一半 → 未完成")
    void pureDocumentSectionIncompleteWhenPartiallyOpened() {
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of(section(21L, CV1, 0)));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version(CV1, COURSE_1)));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course(COURSE_1)));
        Mockito.when(packageCourseMapper.selectList(Mockito.any())).thenReturn(List.of(link(CV1, 1)));
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of(
                binding(CV1, 21L, 31L, "PDF"), binding(CV1, 21L, 32L, "PPTX")));
        Mockito.when(resourceMapper.selectList(Mockito.any()))
                .thenReturn(List.of(resource(31L, "PDF"), resource(32L, "PPTX")));
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(openLogMapper.selectList(Mockito.any())).thenReturn(List.of(open(31L)));

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.sectionDone()).isZero();
    }

    @Test
    @DisplayName("已完成任务再计算不会回退（不调用 completeIfNotCompleted 之外的状态写）")
    void completedStateDoesNotRegress() {
        Mockito.when(assignmentMapper.selectById(ASSIGNMENT_ID)).thenReturn(assignment("COMPLETED"));
        Mockito.when(recordMapper.selectList(Mockito.any())).thenReturn(List.of());

        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);

        assertThat(p.completed()).isTrue();
        Mockito.verify(assignmentMapper, Mockito.never())
                .completeIfNotCompleted(Mockito.anyLong(), Mockito.any());
    }

    @Test
    @DisplayName("章节时长缺失且无资源 → 视为未完成，不拖垮整体进度查询")
    void sectionWithoutDurationOrResourcesIsIncomplete() {
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of(section(21L, CV1, null)));
        Mockito.when(courseVersionMapper.selectList(Mockito.any())).thenReturn(List.of(version(CV1, COURSE_1)));
        Mockito.when(courseMapper.selectList(Mockito.any())).thenReturn(List.of(course(COURSE_1)));
        Mockito.when(packageCourseMapper.selectList(Mockito.any())).thenReturn(List.of(link(CV1, 1)));
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of());

        // 只读进度视角：数据缺陷记为「未完成」，让列表/首页仍可展示；
        // 「拒绝开启学习」的硬失败由心跳路径（LearningService）负责，见 LearningServiceTest。
        LearningProgressService.Progress p = service.recalculate(ASSIGNMENT_ID);
        assertThat(p.sectionDone()).isZero();
        assertThat(p.completed()).isFalse();
    }

    // ---------- fixtures ----------

    private static TrainingAssignment assignment(String status) {
        TrainingAssignment a = new TrainingAssignment();
        a.setId(ASSIGNMENT_ID);
        a.setTaskId(50L);
        a.setEmployeeId(7L);
        a.setPackageVersionId(PACKAGE_VERSION_ID);
        a.setStatus(status);
        return a;
    }

    private static PackageCourse link(long courseVersionId, int sortNo) {
        PackageCourse c = new PackageCourse();
        c.setId(70L + sortNo);
        c.setPackageVersionId(PACKAGE_VERSION_ID);
        c.setCourseVersionId(courseVersionId);
        c.setSortNo(sortNo);
        return c;
    }

    private static CourseVersion version(long id, long courseId) {
        CourseVersion v = new CourseVersion();
        v.setId(id);
        v.setCourseId(courseId);
        v.setVersionNo(1);
        v.setTitle("v" + id);
        v.setStatus("PUBLISHED");
        return v;
    }

    private static com.insurance.training.content.entity.Course course(long id) {
        com.insurance.training.content.entity.Course c = new com.insurance.training.content.entity.Course();
        c.setId(id);
        c.setInsuranceCompanyId(10L);
        c.setTitle("c" + id);
        c.setStatus("PUBLISHED");
        return c;
    }

    private static CourseSection section(long id, long courseVersionId, Integer duration) {
        CourseSection s = new CourseSection();
        s.setId(id);
        s.setCourseVersionId(courseVersionId);
        s.setTitle("s" + id);
        s.setSortNo(1);
        s.setDurationSeconds(duration);
        return s;
    }

    private static CourseVersionResource binding(long cvId, long sectionId, long resourceId, String kind) {
        CourseVersionResource b = new CourseVersionResource();
        b.setId(2000L + resourceId);
        b.setCourseVersionId(cvId);
        b.setSectionId(sectionId);
        b.setResourceId(resourceId);
        b.setSortNo(1);
        return b;
    }

    private static CourseResource resource(long id, String kind) {
        return resource(id, kind, 600);
    }

    /** 时长来自资源（播放器上报），不再是章节手填值。 */
    private static CourseResource resource(long id, String kind, Integer durationSeconds) {
        CourseResource r = new CourseResource();
        r.setId(id);
        r.setCourseId(5L);
        r.setKind(kind);
        r.setObjectKey("k/" + id);
        r.setOriginalName("f" + id);
        r.setDurationSeconds("VIDEO".equals(kind) ? durationSeconds : null);
        r.setStatus("ACTIVE");
        return r;
    }

    /** 一条「该视频已播完」的记录。 */
    private static LearningRecord done(long sectionId, long resourceId) {
        LearningRecord r = new LearningRecord();
        r.setId(resourceId);
        r.setAssignmentId(ASSIGNMENT_ID);
        r.setCourseVersionId(CV1);
        r.setSectionId(sectionId);
        r.setResourceId(resourceId);
        r.setCompleted(1);
        r.setEffectiveSeconds(0);
        return r;
    }

    private static ResourceOpenLog open(long resourceId) {
        ResourceOpenLog o = new ResourceOpenLog();
        o.setId(1L);
        o.setAssignmentId(ASSIGNMENT_ID);
        o.setResourceId(resourceId);
        return o;
    }
}