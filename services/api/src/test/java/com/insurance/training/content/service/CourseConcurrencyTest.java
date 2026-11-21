package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.dto.CourseRequest;
import com.insurance.training.content.dto.CourseVersionRequest;
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
import com.insurance.training.content.vo.CourseVersionVo;
import com.insurance.training.content.vo.CourseVo;
import com.insurance.training.tenancy.service.InsuranceCompanyService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 课程与版本的并发写安全：所有「读-判断-写」路径都必须用条件更新收口，
 * 影响行数为 0 时不得静默覆盖已发布快照。
 */
class CourseConcurrencyTest {

    private static final String IP = "127.0.0.1";
    private static final CurrentUser ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper versionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseSectionMapper sectionMapper = Mockito.mock(CourseSectionMapper.class);
    private final CourseResourceMapper resourceMapper = Mockito.mock(CourseResourceMapper.class);
    private final CourseVersionResourceMapper bindingMapper = Mockito.mock(CourseVersionResourceMapper.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);

    private final CourseService courseService =
            new CourseService(courseMapper, versionMapper, companyService, auditService, 200);
    private final CourseVersionService versionService = new CourseVersionService(
            courseService, courseMapper, versionMapper, sectionMapper, resourceMapper, bindingMapper, auditService);

    /** 让 Lambda 列名解析可用，以便断言真实生成的 SQL 条件（而非只断言 mock 行为）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Course.class);
        TableInfoHelper.initTableInfo(assistant, CourseVersion.class);
    }

    @BeforeEach
    void bindContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        CurrentUserHolder.set(ADMIN);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- CourseService.update：DRAFT 标题同步 ----------

    @Test
    @DisplayName("课程改名同步草稿标题走条件更新，SQL 带 status=DRAFT，不用 updateById")
    void courseUpdateSyncsDraftConditionally() {
        givenWritableCourse(1L, "旧标题");
        CourseVersion draft = draft(11L, 1L, 2, "DRAFT");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any())).thenReturn(draft);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(1);

        CourseVo vo = courseService.update(ADMIN, 1L, new CourseRequest("新标题", "新简介", null), IP);

        assertThat(vo.title()).isEqualTo("新标题");
        LambdaUpdateWrapper<CourseVersion> wrapper = captureVersionUpdate();
        assertThat(wrapper.getSqlSegment()).contains("status");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("DRAFT");
        assertThat(wrapper.getSqlSet()).contains("title");
        Mockito.verify(versionMapper, Mockito.never()).updateById(Mockito.any(CourseVersion.class));
    }

    @Test
    @DisplayName("草稿在同步前被并发发布：跳过同步，课程改名仍成功，快照不被覆盖")
    void courseUpdateSkipsSyncWhenDraftPublishedConcurrently() {
        givenWritableCourse(1L, "旧标题");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(draft(11L, 1L, 2, "DRAFT"));
        // 条件更新影响 0 行 = 该版本已不再是 DRAFT
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(0);

        CourseVo vo = courseService.update(ADMIN, 1L, new CourseRequest("新标题", null, null), IP);

        assertThat(vo.title()).isEqualTo("新标题");
        Mockito.verify(courseMapper).updateById(Mockito.any(Course.class));
        Mockito.verify(versionMapper, Mockito.never()).updateById(Mockito.any(CourseVersion.class));
    }

    @Test
    @DisplayName("课程没有草稿版本时完全不触碰版本表")
    void courseUpdateWithoutDraftDoesNotTouchVersions() {
        givenWritableCourse(1L, "旧标题");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any())).thenReturn(null);

        courseService.update(ADMIN, 1L, new CourseRequest("新标题", null, null), IP);

        Mockito.verify(versionMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any());
        Mockito.verify(versionMapper, Mockito.never()).updateById(Mockito.any(CourseVersion.class));
    }

    // ---------- CourseVersionService.update ----------

    @Test
    @DisplayName("改版本标题时并发发布抢先：条件更新 0 行 → 409")
    void versionUpdateConflictsWhenPublishedConcurrently() {
        givenWritableCourse(1L, "课程");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(draft(11L, 1L, 2, "DRAFT"));
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(0);

        assertThatThrownBy(() -> versionService.update(ADMIN, 1L, 2,
                new CourseVersionRequest("改标题", null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(auditService, Mockito.never())
                .record(Mockito.any(), Mockito.eq("VERSION_UPDATED"), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("版本仍为 DRAFT：条件更新 1 行 → 标题生效")
    void versionUpdateSucceedsWhileStillDraft() {
        givenWritableCourse(1L, "课程");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(draft(11L, 1L, 2, "DRAFT"));
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(1);

        CourseVersionVo vo = versionService.update(ADMIN, 1L, 2, new CourseVersionRequest("改标题", null), IP);

        assertThat(vo.title()).isEqualTo("改标题");
        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(captureVersionUpdate().getParamNameValuePairs()).containsValue("DRAFT");
    }

    // ---------- CourseVersionService.publish ----------

    @Test
    @DisplayName("发布：版本状态 CAS 失败 → 409，且绝不推进课程指针")
    void publishConflictsWhenVersionCasLoses() {
        givenPublishable(1L, 2, null);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(0);

        assertThatThrownBy(() -> versionService.publish(ADMIN, 1L, 2, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(courseMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<Course>>any());
    }

    @Test
    @DisplayName("发布：课程指针 CAS 失败（已被更高版本抢占）→ 409，事务回滚版本状态")
    void publishConflictsWhenCoursePointerCasLoses() {
        givenPublishable(1L, 2, null);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(1);
        Mockito.when(courseMapper.update(Mockito.isNull(), Mockito.<Wrapper<Course>>any())).thenReturn(0);

        assertThatThrownBy(() -> versionService.publish(ADMIN, 1L, 2, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(auditService, Mockito.never())
                .record(Mockito.any(), Mockito.eq("VERSION_PUBLISHED"), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("发布成功：版本 CAS 带 status=DRAFT，课程指针 CAS 带 published_version_no 不回退条件")
    void publishSucceedsWhenBothCasWin() {
        givenPublishable(1L, 2, null);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any())).thenReturn(1);
        Mockito.when(courseMapper.update(Mockito.isNull(), Mockito.<Wrapper<Course>>any())).thenReturn(1);

        CourseVersionVo vo = versionService.publish(ADMIN, 1L, 2, IP);

        assertThat(vo.status()).isEqualTo("PUBLISHED");
        assertThat(captureVersionUpdate().getParamNameValuePairs()).containsValue("DRAFT");

        LambdaUpdateWrapper<Course> courseWrapper = captureCourseUpdate();
        assertThat(courseWrapper.getSqlSegment()).contains("published_version_no");
        assertThat(courseWrapper.getSqlSet()).contains("published_version_no");
        assertThat(courseWrapper.getParamNameValuePairs()).containsValue(2);
    }

    @Test
    @DisplayName("重复发布已发布版本：在任何写操作前就 409")
    void publishRejectsAlreadyPublishedVersion() {
        Course course = givenWritableCourse(1L, "课程");
        course.setPublishedVersionNo(2);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(draft(11L, 1L, 2, "PUBLISHED"));

        assertThatThrownBy(() -> versionService.publish(ADMIN, 1L, 2, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any());
        Mockito.verify(courseMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<Course>>any());
    }

    @Test
    @DisplayName("发布早于当前生效版本的历史版本 → 409（回滚只能走 copy）")
    void publishRejectsOlderVersion() {
        Course course = givenWritableCourse(1L, "课程");
        course.setPublishedVersionNo(3);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(draft(11L, 1L, 2, "DRAFT"));

        assertThatThrownBy(() -> versionService.publish(ADMIN, 1L, 2, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<CourseVersion>>any());
    }

    // ---------- latest_version_no 原子递增 ----------

    @Test
    @DisplayName("新建版本：版本号 CAS 失败 → 409，不插入版本行")
    void createConflictsWhenVersionNoCasLoses() {
        givenWritableCourse(1L, "课程");
        Mockito.when(courseMapper.update(Mockito.isNull(), Mockito.<Wrapper<Course>>any())).thenReturn(0);

        assertThatThrownBy(() -> versionService.create(ADMIN, 1L, new CourseVersionRequest(null, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).insert(Mockito.any(CourseVersion.class));
    }

    @Test
    @DisplayName("复制版本：版本号 CAS 失败 → 409，不产生半成品草稿")
    void copyConflictsWhenVersionNoCasLoses() {
        givenWritableCourse(1L, "课程");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(draft(11L, 1L, 1, "PUBLISHED"));
        Mockito.when(courseMapper.update(Mockito.isNull(), Mockito.<Wrapper<Course>>any())).thenReturn(0);

        assertThatThrownBy(() -> versionService.copy(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).insert(Mockito.any(CourseVersion.class));
        Mockito.verify(sectionMapper, Mockito.never()).insert(Mockito.any(CourseSection.class));
    }

    @Test
    @DisplayName("版本号递增成功时 SQL 带 latest_version_no = 旧值 的乐观条件")
    void versionNoBumpCarriesOldValueCondition() {
        givenWritableCourse(1L, "课程");
        Mockito.when(courseMapper.update(Mockito.isNull(), Mockito.<Wrapper<Course>>any())).thenReturn(1);
        Mockito.when(versionMapper.insert(Mockito.any(CourseVersion.class))).thenAnswer(inv -> {
            inv.<CourseVersion>getArgument(0).setId(99L);
            return 1;
        });

        CourseVersionVo vo = versionService.create(ADMIN, 1L, new CourseVersionRequest(null, null), IP);

        assertThat(vo.versionNo()).isEqualTo(2);
        LambdaUpdateWrapper<Course> wrapper = captureCourseUpdate();
        assertThat(wrapper.getSqlSegment()).contains("latest_version_no");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(1).containsValue(2);
    }

    // ---------- fixtures ----------

    private Course givenWritableCourse(long courseId, String title) {
        Course course = new Course();
        course.setId(courseId);
        course.setInsuranceCompanyId(10L);
        course.setOwnerEnterpriseId(null);
        course.setTitle(title);
        course.setStatus("DRAFT");
        course.setLatestVersionNo(1);
        Mockito.when(courseMapper.selectById(courseId)).thenReturn(course);
        return course;
    }

    /** 一个章节 + 一条绑定 + 一个 ACTIVE 资源，满足发布完整性校验。 */
    private void givenPublishable(long courseId, int versionNo, Integer publishedVersionNo) {
        Course course = givenWritableCourse(courseId, "课程");
        course.setPublishedVersionNo(publishedVersionNo);
        CourseVersion version = draft(11L, courseId, versionNo, "DRAFT");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<CourseVersion>>any())).thenReturn(version);

        CourseSection section = new CourseSection();
        section.setId(21L);
        section.setCourseVersionId(11L);
        section.setTitle("第一章");
        section.setSortNo(1);
        section.setDurationSeconds(600);
        Mockito.when(sectionMapper.selectList(Mockito.<Wrapper<CourseSection>>any())).thenReturn(List.of(section));

        CourseVersionResource binding = new CourseVersionResource();
        binding.setId(31L);
        binding.setCourseVersionId(11L);
        binding.setSectionId(21L);
        binding.setResourceId(41L);
        Mockito.when(bindingMapper.selectList(Mockito.<Wrapper<CourseVersionResource>>any()))
                .thenReturn(List.of(binding));

        CourseResource resource = new CourseResource();
        resource.setId(41L);
        resource.setCourseId(courseId);
        resource.setKind("VIDEO");
        resource.setStatus("ACTIVE");
        Mockito.when(resourceMapper.selectList(Mockito.<Wrapper<CourseResource>>any())).thenReturn(List.of(resource));

        Mockito.when(sectionMapper.selectCount(Mockito.<Wrapper<CourseSection>>any())).thenReturn(1L);
        Mockito.when(bindingMapper.selectCount(Mockito.<Wrapper<CourseVersionResource>>any())).thenReturn(1L);
    }

    private CourseVersion draft(long id, long courseId, int versionNo, String status) {
        CourseVersion version = new CourseVersion();
        version.setId(id);
        version.setCourseId(courseId);
        version.setVersionNo(versionNo);
        version.setTitle("版本标题");
        version.setStatus(status);
        return version;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<CourseVersion> captureVersionUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<CourseVersion>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(versionMapper).update(Mockito.isNull(), captor.capture());
        return materialize(captor.getValue());
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Course> captureCourseUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<Course>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(courseMapper).update(Mockito.isNull(), captor.capture());
        return materialize(captor.getValue());
    }

    /** WHERE 段是惰性求值的：不先渲染 getSqlSegment()，paramNameValuePairs 里只有 SET 的值。 */
    private static <T, W extends LambdaUpdateWrapper<T>> W materialize(W wrapper) {
        wrapper.getSqlSegment();
        return wrapper;
    }
}