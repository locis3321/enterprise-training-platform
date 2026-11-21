package com.insurance.training.training.service;

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
import com.insurance.training.tenancy.service.InsuranceCompanyService;
import com.insurance.training.training.dto.PackageCourseRequest;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 培训包关联课程版本。
 *
 * <p>关键不变量：关联写入必须与「版本仍为 DRAFT」在同一语句内判定，
 * 否则并发发布会把课程追加进已冻结的快照。
 */
class PackageCourseServiceTest {

    private static final String IP = "127.0.0.1";
    private static final CurrentUser ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    private final TrainingPackageMapper packageMapper = Mockito.mock(TrainingPackageMapper.class);
    private final TrainingPackageVersionMapper versionMapper = Mockito.mock(TrainingPackageVersionMapper.class);
    private final PackageCourseMapper packageCourseMapper = Mockito.mock(PackageCourseMapper.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper courseVersionMapper = Mockito.mock(CourseVersionMapper.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);

    private final PackageService packageService =
            new PackageService(packageMapper, versionMapper, companyService, auditService, 200);
    private final PackageVersionService versionService = new PackageVersionService(
            packageService, packageMapper, versionMapper, packageCourseMapper, auditService);
    private final PackageCourseService service = new PackageCourseService(
            packageService, versionService, versionMapper, packageCourseMapper,
            courseMapper, courseVersionMapper, auditService);

    @BeforeEach
    void bindContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        CurrentUserHolder.set(ADMIN);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- 关联校验 ----------

    @Test
    @DisplayName("草稿课程版本不能进包 → 409，且不写任何关联行")
    void draftCourseVersionIsRejected() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 10L, "DRAFT");

        assertThatThrownBy(() -> service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 1), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageCourseMapper, Mockito.never()).insertIfDraft(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
        Mockito.verify(packageCourseMapper, Mockito.never()).insert(Mockito.any(PackageCourse.class));
    }

    @Test
    @DisplayName("课程版本不存在 → 404")
    void missingCourseVersionIsNotFound() {
        givenDraftPackageVersion();
        Mockito.when(courseVersionMapper.selectById(90L)).thenReturn(null);

        assertThatThrownBy(() -> service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 1), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("跨集团关联课程版本 → 403（租户隔离在关联入口就拒绝）")
    void crossCompanyCourseVersionIsForbidden() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 99L, "PUBLISHED");

        assertThatThrownBy(() -> service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 1), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verify(packageCourseMapper, Mockito.never()).insertIfDraft(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    @DisplayName("已发布的包版本不可再关联 → 409，且不调用条件写入")
    void publishedPackageVersionRejectsBinding() {
        givenPackage();
        Mockito.when(versionMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(packageVersion("PUBLISHED"));

        assertThatThrownBy(() -> service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 1), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageCourseMapper, Mockito.never()).insertIfDraft(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
        Mockito.verifyNoInteractions(courseVersionMapper);
    }

    // ---------- 关联写入 ----------

    @Test
    @DisplayName("关联成功走单语句条件写入 insertIfDraft，而不是先查后插的 insert")
    void bindUsesConditionalInsert() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 10L, "PUBLISHED");
        Mockito.when(packageCourseMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<PackageCourse>>any()))
                .thenReturn(null);
        Mockito.when(packageCourseMapper.insertIfDraft(11L, 90L, 3)).thenReturn(1);

        service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 3), IP);

        Mockito.verify(packageCourseMapper).insertIfDraft(11L, 90L, 3);
        Mockito.verify(packageCourseMapper, Mockito.never()).insert(Mockito.any(PackageCourse.class));
        Mockito.verify(auditService).record(Mockito.eq(ADMIN), Mockito.eq("PACKAGE_COURSE_BOUND"),
                Mockito.eq("training_package_version"), Mockito.eq(11L), Mockito.anyString(), Mockito.eq(IP));
    }

    @Test
    @DisplayName("条件写入影响 0 行（select 后被并发发布）→ 409，快照不被污染")
    void bindConflictsWhenVersionPublishedConcurrently() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 10L, "PUBLISHED");
        Mockito.when(packageCourseMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<PackageCourse>>any()))
                .thenReturn(null);
        Mockito.when(packageCourseMapper.insertIfDraft(11L, 90L, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 1), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(auditService, Mockito.never()).record(Mockito.any(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("并发插入同一关联触发唯一键 → 幂等成功，不报错")
    void duplicateBindingIsIdempotent() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 10L, "PUBLISHED");
        Mockito.when(packageCourseMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<PackageCourse>>any()))
                .thenReturn(null);
        Mockito.when(packageCourseMapper.insertIfDraft(11L, 90L, 1))
                .thenThrow(new DuplicateKeyException("uk_package_course"));

        service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 1), IP);

        Mockito.verify(packageCourseMapper).insertIfDraft(11L, 90L, 1);
    }

    @Test
    @DisplayName("重复关联同一课程版本：幂等并按新 sortNo 条件更新，实现重排")
    void rebindingUpdatesSortNoConditionally() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 10L, "PUBLISHED");
        PackageCourse existing = new PackageCourse();
        existing.setId(70L);
        existing.setPackageVersionId(11L);
        existing.setCourseVersionId(90L);
        existing.setSortNo(1);
        Mockito.when(packageCourseMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<PackageCourse>>any()))
                .thenReturn(existing);
        Mockito.when(packageCourseMapper.updateSortNoIfDraft(70L, 5)).thenReturn(1);

        service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 5), IP);

        Mockito.verify(packageCourseMapper).updateSortNoIfDraft(70L, 5);
        Mockito.verify(packageCourseMapper, Mockito.never()).insertIfDraft(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    }

    @Test
    @DisplayName("重排时条件更新影响 0 行（并发发布）→ 409")
    void reorderConflictsWhenVersionPublishedConcurrently() {
        givenDraftPackageVersion();
        givenCourseVersion(90L, 5L, 10L, "PUBLISHED");
        PackageCourse existing = new PackageCourse();
        existing.setId(70L);
        existing.setPackageVersionId(11L);
        existing.setCourseVersionId(90L);
        existing.setSortNo(1);
        Mockito.when(packageCourseMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<PackageCourse>>any()))
                .thenReturn(existing);
        Mockito.when(packageCourseMapper.updateSortNoIfDraft(70L, 5)).thenReturn(0);

        assertThatThrownBy(() -> service.bind(ADMIN, 1L, 1, new PackageCourseRequest(90L, 5), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------- 解除关联 ----------

    @Test
    @DisplayName("解除关联走条件删除 deleteIfDraft，不用 deleteById")
    void unbindUsesConditionalDelete() {
        givenDraftPackageVersion();
        Mockito.when(packageCourseMapper.deleteIfDraft(11L, 90L)).thenReturn(1);

        service.unbind(ADMIN, 1L, 1, 90L, IP);

        Mockito.verify(packageCourseMapper).deleteIfDraft(11L, 90L);
        Mockito.verify(packageCourseMapper, Mockito.never()).deleteById(Mockito.anyLong());
    }

    @Test
    @DisplayName("条件删除 0 行且版本仍为 DRAFT → 404（该课程版本未关联）")
    void unbindMissingLinkIsNotFound() {
        givenDraftPackageVersion();
        Mockito.when(packageCourseMapper.deleteIfDraft(11L, 90L)).thenReturn(0);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(packageVersion("DRAFT"));

        assertThatThrownBy(() -> service.unbind(ADMIN, 1L, 1, 90L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("条件删除 0 行且版本已被并发发布 → 409，而不是误报 404")
    void unbindConflictsWhenVersionPublishedConcurrently() {
        givenDraftPackageVersion();
        Mockito.when(packageCourseMapper.deleteIfDraft(11L, 90L)).thenReturn(0);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(packageVersion("PUBLISHED"));

        assertThatThrownBy(() -> service.unbind(ADMIN, 1L, 1, 90L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("已发布包版本解除关联 → 409，在删除前就拒绝")
    void publishedPackageVersionRejectsUnbind() {
        givenPackage();
        Mockito.when(versionMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(packageVersion("PUBLISHED"));

        assertThatThrownBy(() -> service.unbind(ADMIN, 1L, 1, 90L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageCourseMapper, Mockito.never()).deleteIfDraft(Mockito.anyLong(), Mockito.anyLong());
    }

    // ---------- fixtures ----------

    private void givenPackage() {
        TrainingPackage pkg = new TrainingPackage();
        pkg.setId(1L);
        pkg.setInsuranceCompanyId(10L);
        pkg.setOwnerEnterpriseId(null);
        pkg.setTitle("培训包");
        pkg.setStatus("DRAFT");
        pkg.setLatestVersionNo(1);
        Mockito.when(packageMapper.selectById(1L)).thenReturn(pkg);
    }

    private void givenDraftPackageVersion() {
        givenPackage();
        Mockito.when(versionMapper.selectOne(Mockito.<com.baomidou.mybatisplus.core.conditions.Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(packageVersion("DRAFT"));
    }

    private static TrainingPackageVersion packageVersion(String status) {
        TrainingPackageVersion v = new TrainingPackageVersion();
        v.setId(11L);
        v.setPackageId(1L);
        v.setVersionNo(1);
        v.setTitle("版本标题");
        v.setStatus(status);
        return v;
    }

    private void givenCourseVersion(long courseVersionId, long courseId, long companyId, String status) {
        CourseVersion cv = new CourseVersion();
        cv.setId(courseVersionId);
        cv.setCourseId(courseId);
        cv.setVersionNo(1);
        cv.setTitle("课程版本");
        cv.setStatus(status);
        Mockito.when(courseVersionMapper.selectById(courseVersionId)).thenReturn(cv);

        Course course = new Course();
        course.setId(courseId);
        course.setInsuranceCompanyId(companyId);
        course.setTitle("课程");
        course.setStatus("PUBLISHED");
        Mockito.when(courseMapper.selectById(courseId)).thenReturn(course);
    }
}