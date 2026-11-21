package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.vo.AdminPackageDetailVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 培训包详情：版本列表 + 指定版本的课程清单；版本号不存在不回落最新。 */
class PackageDetailServiceTest {

    private static final CurrentUser COMPANY_ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");
    private static final CurrentUser ENTERPRISE_ADMIN = new CurrentUser(2L, PrincipalType.ADMIN,
            "ENTERPRISE_ADMIN", DataScopeType.ENTERPRISE, 10L, 100L, "企业管理员");

    private final TrainingPackageMapper packageMapper = Mockito.mock(TrainingPackageMapper.class);
    private final TrainingPackageVersionMapper versionMapper = Mockito.mock(TrainingPackageVersionMapper.class);
    private final PackageCourseMapper packageCourseMapper = Mockito.mock(PackageCourseMapper.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper courseVersionMapper = Mockito.mock(CourseVersionMapper.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);

    private final PackageService packageService =
            new PackageService(packageMapper, versionMapper, companyService, auditService, 200);
    private final PackageDetailService service = new PackageDetailService(
            packageService, versionMapper, packageCourseMapper, courseMapper, courseVersionMapper);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingPackage.class);
        TableInfoHelper.initTableInfo(assistant, TrainingPackageVersion.class);
        TableInfoHelper.initTableInfo(assistant, PackageCourse.class);
        TableInfoHelper.initTableInfo(assistant, Course.class);
        TableInfoHelper.initTableInfo(assistant, CourseVersion.class);
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

    @Test
    @DisplayName("缺省取 latest 版本，同时返回全部版本概要")
    void detailDefaultsToLatestVersion() {
        givenPackage(1L, 10L, 2);
        Mockito.when(versionMapper.selectList(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(List.of(packageVersion(12L, 2, "DRAFT"), packageVersion(11L, 1, "PUBLISHED")));
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(packageVersion(12L, 2, "DRAFT"));
        Mockito.when(packageCourseMapper.selectList(Mockito.<Wrapper<PackageCourse>>any())).thenReturn(List.of());

        AdminPackageDetailVo vo = service.adminDetail(COMPANY_ADMIN, 1L, null);

        assertThat(vo.latestVersionNo()).isEqualTo(2);
        assertThat(vo.version().versionNo()).isEqualTo(2);
        assertThat(vo.version().courses()).isEmpty();
        assertThat(vo.versions()).hasSize(2);
    }

    @Test
    @DisplayName("指定版本号不存在 → 404，不回落 latest")
    void missingVersionIsNotFound() {
        givenPackage(1L, 10L, 2);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any())).thenReturn(null);

        assertThatThrownBy(() -> service.adminDetail(COMPANY_ADMIN, 1L, 99))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("课程清单区分快照标题与课程当前标题，并带课程状态供下线提示")
    void courseListSeparatesSnapshotTitleFromCurrentTitle() {
        givenPackage(1L, 10L, 1);
        Mockito.when(versionMapper.selectList(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(List.of(packageVersion(11L, 1, "PUBLISHED")));
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(packageVersion(11L, 1, "PUBLISHED"));
        Mockito.when(packageCourseMapper.selectList(Mockito.<Wrapper<PackageCourse>>any()))
                .thenReturn(List.of(link(71L, 91L, 1), link(70L, 90L, 2)));

        Course course = new Course();
        course.setId(5L);
        course.setInsuranceCompanyId(10L);
        course.setTitle("改名后的课程");
        course.setStatus("OFFLINE");
        Mockito.when(courseMapper.selectList(Mockito.<Wrapper<Course>>any())).thenReturn(List.of(course));

        CourseVersion snapshot = new CourseVersion();
        snapshot.setId(90L);
        snapshot.setCourseId(5L);
        snapshot.setVersionNo(1);
        snapshot.setTitle("发布时的快照名");
        snapshot.setStatus("PUBLISHED");
        CourseVersion other = new CourseVersion();
        other.setId(91L);
        other.setCourseId(5L);
        other.setVersionNo(2);
        other.setTitle("第二版快照名");
        other.setStatus("PUBLISHED");
        Mockito.when(courseVersionMapper.selectList(Mockito.<Wrapper<CourseVersion>>any()))
                .thenReturn(List.of(snapshot, other));

        AdminPackageDetailVo vo = service.adminDetail(COMPANY_ADMIN, 1L, 1);

        assertThat(vo.version().courses()).hasSize(2);
        assertThat(vo.version().courses().get(0).sortNo()).isEqualTo(1);
        assertThat(vo.version().courses().get(0).courseVersionId()).isEqualTo(91L);
        assertThat(vo.version().courses().get(1).courseVersionTitle()).isEqualTo("发布时的快照名");
        assertThat(vo.version().courses().get(1).courseTitle()).isEqualTo("改名后的课程");
        assertThat(vo.version().courses().get(1).courseStatus()).isEqualTo("OFFLINE");
        assertThat(vo.version().courses().get(1).courseVersionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("跨集团读详情 → 403")
    void crossCompanyDetailIsForbidden() {
        givenPackage(1L, 99L, 1);

        assertThatThrownBy(() -> service.adminDetail(COMPANY_ADMIN, 1L, null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verifyNoInteractions(versionMapper);
    }

    @Test
    @DisplayName("企业管理员可读同公司他企业归属的包详情（只读可见）")
    void enterpriseAdminCanReadOtherEnterprisePackageDetail() {
        CurrentUserHolder.set(ENTERPRISE_ADMIN);
        TrainingPackage pkg = givenPackage(1L, 10L, 1);
        pkg.setOwnerEnterpriseId(200L);
        Mockito.when(versionMapper.selectList(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(List.of(packageVersion(11L, 1, "PUBLISHED")));
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(packageVersion(11L, 1, "PUBLISHED"));
        Mockito.when(packageCourseMapper.selectList(Mockito.<Wrapper<PackageCourse>>any())).thenReturn(List.of());

        AdminPackageDetailVo vo = service.adminDetail(ENTERPRISE_ADMIN, 1L, null);

        assertThat(vo.ownerEnterpriseId()).isEqualTo(200L);
    }

    // ---------- fixtures ----------

    private TrainingPackage givenPackage(long id, long companyId, int latestVersionNo) {
        TrainingPackage pkg = new TrainingPackage();
        pkg.setId(id);
        pkg.setInsuranceCompanyId(companyId);
        pkg.setOwnerEnterpriseId(null);
        pkg.setTitle("培训包");
        pkg.setDescription("简介");
        pkg.setStatus("DRAFT");
        pkg.setLatestVersionNo(latestVersionNo);
        Mockito.when(packageMapper.selectById(id)).thenReturn(pkg);
        return pkg;
    }

    private static TrainingPackageVersion packageVersion(long id, int versionNo, String status) {
        TrainingPackageVersion v = new TrainingPackageVersion();
        v.setId(id);
        v.setPackageId(1L);
        v.setVersionNo(versionNo);
        v.setTitle("版本" + versionNo);
        v.setStatus(status);
        return v;
    }

    private static PackageCourse link(long id, long courseVersionId, int sortNo) {
        PackageCourse link = new PackageCourse();
        link.setId(id);
        link.setPackageVersionId(11L);
        link.setCourseVersionId(courseVersionId);
        link.setSortNo(sortNo);
        return link;
    }
}