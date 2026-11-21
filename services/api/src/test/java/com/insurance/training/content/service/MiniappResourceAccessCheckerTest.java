package com.insurance.training.content.service;

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
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学员资源访问授权链。
 *
 * <p>8 步校验逐项覆盖：员工状态、企业状态、课程状态、同公司、已发布版本、
 * 绑定存在、章节同版本、资源 ACTIVE 且属该课程。
 */
class MiniappResourceAccessCheckerTest {

    private final LearnerGuard learnerGuard = Mockito.mock(LearnerGuard.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper versionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseSectionMapper sectionMapper = Mockito.mock(CourseSectionMapper.class);
    private final CourseResourceMapper resourceMapper = Mockito.mock(CourseResourceMapper.class);
    private final CourseVersionResourceMapper bindingMapper = Mockito.mock(CourseVersionResourceMapper.class);

    private final MiniappResourceAccessChecker checker = new MiniappResourceAccessChecker(
            learnerGuard, courseMapper, versionMapper,
            sectionMapper, resourceMapper, bindingMapper);

    private final CurrentUser employee = new CurrentUser(9L, PrincipalType.EMPLOYEE, null,
            DataScopeType.ENTERPRISE, 1L, 1L, "张三");

    @BeforeEach
    void happyPathFixtures() {
        // 账号 id = 9，其名下员工 id = 91（刻意不同，用于验证不再混用两套 ID）
        Mockito.when(learnerGuard.requireLearner(Mockito.any())).thenReturn(learner(9L, 91L, 1L));
        Mockito.when(courseMapper.selectById(10L)).thenReturn(course("PUBLISHED", 1L, 1));
        Mockito.when(versionMapper.selectOne(Mockito.any())).thenReturn(version(100L, 1L, 1));
        Mockito.when(bindingMapper.selectOne(Mockito.any())).thenReturn(binding(100L, 7L, 5L));
        Mockito.when(sectionMapper.selectById(7L)).thenReturn(section(7L, 100L));
        Mockito.when(resourceMapper.selectById(5L)).thenReturn(resource(5L, 10L, "ACTIVE"));
    }

    @Test
    @DisplayName("全部条件满足时放行")
    void allowsWhenAllChecksPass() {
        CourseResource result = checker.requireAccess(employee, 10L, 5L);

        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("LearnerGuard 拒绝（员工或企业停用）→ 403")
    void propagatesGuardRejection() {
        Mockito.when(learnerGuard.requireLearner(Mockito.any()))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "员工档案已停用"));

        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("回归：账号 id 与员工 id 不同也能正常授权（不再混用两套 ID）")
    void usesLearnerContextNotRawAccountId() {
        // 账号 9、员工 91；若无修复，会用 9 去查 employee 而查不到 → 误判 403
        CourseResource result = checker.requireAccess(employee, 10L, 5L);

        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("回归：跨企业学员只要有一个员工属于该公司即可访问（多企业聚合）")
    void allowsWhenAnyEmployeeBelongsToCompany() {
        Mockito.when(learnerGuard.requireLearner(Mockito.any()))
                .thenReturn(new LearnerContext(9L, "13800138000",
                        java.util.List.of(emp(91L, 2L, 1L), emp(92L, 1L, 2L)), emp(91L, 2L, 1L)));

        CourseResource result = checker.requireAccess(employee, 10L, 5L);

        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("名下员工都不属于该课程公司 → 403")
    void rejectsWhenNoEmployeeMatchesCompany() {
        Mockito.when(learnerGuard.requireLearner(Mockito.any()))
                .thenReturn(new LearnerContext(9L, "13800138000",
                        java.util.List.of(emp(91L, 2L, 2L)), emp(91L, 2L, 2L)));

        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("课程已下线/未发布 → 404")
    void rejectsUnpublishedCourse() {
        Mockito.when(courseMapper.selectById(10L)).thenReturn(course("OFFLINE", 1L, 1));

        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("跨集团 → 403")
    void rejectsCrossCompany() {
        Mockito.when(courseMapper.selectById(10L)).thenReturn(course("PUBLISHED", 99L, 1));

        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("资源不属于该版本 → 404")
    void rejectsWhenNoBinding() {
        Mockito.when(bindingMapper.selectOne(Mockito.any())).thenReturn(null);

        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("绑定章节不属于该版本（拼接）→ 404")
    void rejectsSectionFromOtherVersion() {
        Mockito.when(sectionMapper.selectById(7L)).thenReturn(section(7L, 999L));

        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("资源已删除 / 属于其他课程 → 404")
    void rejectsDeletedOrForeignResource() {
        Mockito.when(resourceMapper.selectById(5L)).thenReturn(resource(5L, 10L, "DELETED"));
        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class);

        Mockito.when(resourceMapper.selectById(5L)).thenReturn(resource(5L, 999L, "ACTIVE"));
        assertThatThrownBy(() -> checker.requireAccess(employee, 10L, 5L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("管理端 token 调学员接口 → 403（由 LearnerGuard 拒绝并透传）")
    void rejectsAdminPrincipal() {
        CurrentUser admin = new CurrentUser(1L, PrincipalType.ADMIN, "SUPER_ADMIN",
                DataScopeType.ALL, null, null, "超管");
        // 主体类型检查已下沉到 LearnerGuard，这里验证拒绝被正确透传
        Mockito.when(learnerGuard.requireLearner(admin))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "该接口仅学员可访问"));

        assertThatThrownBy(() -> checker.requireAccess(admin, 10L, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---- fixtures ----

    /** 单个启用员工。 */
    private Employee emp(long id, long enterpriseId, long companyId) {
        Employee e = new Employee();
        e.setId(id);
        e.setEnterpriseId(enterpriseId);
        e.setInsuranceCompanyId(companyId);
        e.setStatus(1);
        return e;
    }

    /** 账号 id 与员工 id 刻意不同。 */
    private LearnerContext learner(long accountId, long employeeId, long companyId) {
        Employee e = emp(employeeId, 1L, companyId);
        return new LearnerContext(accountId, "13800138000", java.util.List.of(e), e);
    }

    private Course course(String status, long companyId, Integer publishedVersionNo) {
        Course c = new Course();
        c.setId(10L);
        c.setStatus(status);
        c.setInsuranceCompanyId(companyId);
        c.setPublishedVersionNo(publishedVersionNo);
        return c;
    }

    private CourseVersion version(long id, long courseId, int versionNo) {
        CourseVersion v = new CourseVersion();
        v.setId(id);
        v.setCourseId(courseId);
        v.setVersionNo(versionNo);
        v.setStatus("PUBLISHED");
        return v;
    }

    private CourseVersionResource binding(long versionId, long sectionId, long resourceId) {
        CourseVersionResource b = new CourseVersionResource();
        b.setCourseVersionId(versionId);
        b.setSectionId(sectionId);
        b.setResourceId(resourceId);
        return b;
    }

    private CourseSection section(long id, long versionId) {
        CourseSection s = new CourseSection();
        s.setId(id);
        s.setCourseVersionId(versionId);
        return s;
    }

    private CourseResource resource(long id, long courseId, String status) {
        CourseResource r = new CourseResource();
        r.setId(id);
        r.setCourseId(courseId);
        r.setStatus(status);
        r.setKind("PDF");
        r.setObjectKey("k");
        return r;
    }
}
