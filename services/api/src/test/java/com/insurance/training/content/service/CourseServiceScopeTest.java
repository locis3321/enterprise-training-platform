package com.insurance.training.content.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.dto.CourseRequest;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 课程归属与写权限：企业管理员不能伪造归属，也不能改他人课程。 */
class CourseServiceScopeTest {

    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper versionMapper = Mockito.mock(CourseVersionMapper.class);
    private final com.insurance.training.tenancy.service.InsuranceCompanyService companyService =
            Mockito.mock(com.insurance.training.tenancy.service.InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final CourseService service = new CourseService(courseMapper, versionMapper, companyService, auditService, 200);

    private static final CurrentUser ENTERPRISE_ADMIN = new CurrentUser(2L, PrincipalType.ADMIN,
            "ENTERPRISE_ADMIN", DataScopeType.ENTERPRISE, 10L, 100L, "企业管理员");
    private static final CurrentUser COMPANY_ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    @BeforeEach
    void bindContext() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("企业管理员建课：归属被强制为本企业，无法通过请求体伪造")
    void enterpriseAdminOwnershipIsForced() {
        CurrentUserHolder.set(ENTERPRISE_ADMIN);
        Mockito.when(courseMapper.insert(Mockito.any(Course.class))).thenAnswer(inv -> {
            Course c = inv.getArgument(0);
            c.setId(1L);
            return 1;
        });

        service.create(ENTERPRISE_ADMIN, new CourseRequest("安全用电", "简介", null), "127.0.0.1");

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        Mockito.verify(courseMapper).insert(captor.capture());
        Course saved = captor.getValue();
        assertThat(saved.getOwnerEnterpriseId()).isEqualTo(100L);
        assertThat(saved.getInsuranceCompanyId()).isEqualTo(10L);
        assertThat(saved.getLatestVersionNo()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("企业管理员建课会同时创建 v1 草稿")
    void createAlsoCreatesFirstDraft() {
        CurrentUserHolder.set(ENTERPRISE_ADMIN);
        Mockito.when(courseMapper.insert(Mockito.any(Course.class))).thenAnswer(inv -> {
            Course c = inv.getArgument(0);
            c.setId(9L);
            return 1;
        });

        service.create(ENTERPRISE_ADMIN, new CourseRequest("消防基础", null, null), "127.0.0.1");

        ArgumentCaptor<CourseVersion> versionCaptor = ArgumentCaptor.forClass(CourseVersion.class);
        Mockito.verify(versionMapper).insert(versionCaptor.capture());
        CourseVersion draft = versionCaptor.getValue();
        assertThat(draft.getCourseId()).isEqualTo(9L);
        assertThat(draft.getVersionNo()).isEqualTo(1);
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("公司管理员建课为公司级（owner 为 null）")
    void companyAdminCreatesCompanyLevelCourse() {
        CurrentUserHolder.set(COMPANY_ADMIN);
        Mockito.when(courseMapper.insert(Mockito.any(Course.class))).thenAnswer(inv -> {
            Course c = inv.getArgument(0);
            c.setId(2L);
            return 1;
        });

        service.create(COMPANY_ADMIN, new CourseRequest("公司统一课", null, null), "127.0.0.1");

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        Mockito.verify(courseMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerEnterpriseId()).isNull();
    }

    @Test
    @DisplayName("超级管理员可为指定公司建课（公司须存在）")
    void superAdminCanCreateForAnyCompany() {
        CurrentUser superAdmin = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
                DataScopeType.ALL, null, null, "平台超管");
        CurrentUserHolder.set(superAdmin);
        Mockito.when(courseMapper.insert(Mockito.any(Course.class))).thenAnswer(inv -> {
            Course c = inv.getArgument(0);
            c.setId(11L);
            return 1;
        });

        service.create(superAdmin, new CourseRequest("平台统一课", null, 20L), "127.0.0.1");

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        Mockito.verify(courseMapper).insert(captor.capture());
        assertThat(captor.getValue().getInsuranceCompanyId()).isEqualTo(20L);
        assertThat(captor.getValue().getOwnerEnterpriseId()).isNull();
        Mockito.verify(companyService).requireExists(20L);
    }

    @Test
    @DisplayName("超级管理员未指定公司时明确失败，不静默落到任意公司")
    void superAdminWithoutCompanyFails() {
        CurrentUser superAdmin = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
                DataScopeType.ALL, null, null, "平台超管");
        CurrentUserHolder.set(superAdmin);

        assertThatThrownBy(() -> service.create(superAdmin, new CourseRequest("x", null, null), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        Mockito.verifyNoInteractions(courseMapper);
    }

    @Test
    @DisplayName("企业管理员修改他企业归属的课程 → 403")
    void enterpriseAdminCannotWriteOtherEnterpriseCourse() {
        Course course = new Course();
        course.setId(5L);
        course.setInsuranceCompanyId(10L);
        course.setOwnerEnterpriseId(200L);   // 属于另一企业
        Mockito.when(courseMapper.selectById(5L)).thenReturn(course);

        assertThatThrownBy(() -> service.getWritable(ENTERPRISE_ADMIN, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("企业管理员可读同公司他企业课程（可见但不只写）")
    void enterpriseAdminCanReadOtherEnterpriseCourse() {
        Course course = new Course();
        course.setId(6L);
        course.setInsuranceCompanyId(10L);
        course.setOwnerEnterpriseId(200L);
        Mockito.when(courseMapper.selectById(6L)).thenReturn(course);

        Course visible = service.getVisible(ENTERPRISE_ADMIN, 6L);

        assertThat(visible.getId()).isEqualTo(6L);
    }

    @Test
    @DisplayName("跨集团访问课程 → 403")
    void crossCompanyIsForbidden() {
        Course course = new Course();
        course.setId(7L);
        course.setInsuranceCompanyId(99L);
        Mockito.when(courseMapper.selectById(7L)).thenReturn(course);

        assertThatThrownBy(() -> service.getVisible(COMPANY_ADMIN, 7L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("公司级课程企业管理员只读：修改被拒")
    void companyLevelCourseReadOnlyForEnterpriseAdmin() {
        Course course = new Course();
        course.setId(8L);
        course.setInsuranceCompanyId(10L);
        course.setOwnerEnterpriseId(null);
        Mockito.when(courseMapper.selectById(8L)).thenReturn(course);

        assertThatThrownBy(() -> service.getWritable(ENTERPRISE_ADMIN, 8L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
