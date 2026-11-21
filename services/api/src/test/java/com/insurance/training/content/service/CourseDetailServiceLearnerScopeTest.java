package com.insurance.training.content.service;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 学员课程库的授权回归。
 *
 * <p>核心回归点：**JWT 的 sub 是 learner_account.id，不是 employee.id**。
 * V5 账号化后若仍用 {@code user.id()} 查 employee，会对存量学员误判 403；
 * ID 碰撞时更会按无关员工的公司归属授权。
 */
class CourseDetailServiceLearnerScopeTest {

    private final LearnerGuard learnerGuard = Mockito.mock(LearnerGuard.class);
    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper versionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseSectionMapper sectionMapper = Mockito.mock(CourseSectionMapper.class);
    private final CourseResourceMapper resourceMapper = Mockito.mock(CourseResourceMapper.class);
    private final CourseVersionResourceMapper bindingMapper = Mockito.mock(CourseVersionResourceMapper.class);

    private final CourseDetailService service = new CourseDetailService(
            Mockito.mock(CourseService.class), courseMapper, versionMapper, sectionMapper,
            resourceMapper, bindingMapper, learnerGuard, 200);

    /** 账号 id = 7，员工 id = 71（刻意不同）。 */
    private static final CurrentUser LEARNER = new CurrentUser(7L, PrincipalType.EMPLOYEE, null,
            DataScopeType.ENTERPRISE, 10L, 100L, "张三");

    @BeforeEach
    void fixtures() {
        Mockito.when(learnerGuard.requireLearner(Mockito.any())).thenReturn(
                new LearnerContext(7L, "13800138000",
                        List.of(emp(71L, 100L, 10L)), emp(71L, 100L, 10L)));
        Mockito.when(courseMapper.selectPage(Mockito.any(), Mockito.any())).thenReturn(emptyPage());
        Mockito.when(courseMapper.selectById(5L)).thenReturn(publishedCourse(5L, 10L));
        Mockito.when(versionMapper.selectOne(Mockito.any())).thenReturn(publishedVersion(50L, 5L));
        Mockito.when(sectionMapper.selectList(Mockito.any())).thenReturn(List.of());
        Mockito.when(bindingMapper.selectList(Mockito.any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("回归：账号 id ≠ 员工 id 时课程列表仍可访问（不误判 403）")
    void courseListUsesLearnerContext() {
        var result = service.miniappCourses(LEARNER, 1L, 10L);

        assertThat(result.total()).isZero();
        Mockito.verify(learnerGuard).requireLearner(LEARNER);
    }

    @Test
    @DisplayName("回归：账号 id ≠ 员工 id 时课程详情仍可访问（不误判 403）")
    void courseDetailUsesLearnerContext() {
        var detail = service.miniappDetail(LEARNER, 5L);

        assertThat(detail.id()).isEqualTo(5L);
    }

    @Test
    @DisplayName("课程不属于学员任一员工的公司 → 403")
    void rejectsCrossCompany() {
        Mockito.when(courseMapper.selectById(5L)).thenReturn(publishedCourse(5L, 99L));

        assertThatThrownBy(() -> service.miniappDetail(LEARNER, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("跨企业学员：任一员工属于该公司即可访问")
    void allowsWhenAnyEmployeeMatchesCompany() {
        Mockito.when(learnerGuard.requireLearner(Mockito.any())).thenReturn(
                new LearnerContext(7L, "13800138000",
                        List.of(emp(71L, 100L, 20L), emp(72L, 200L, 10L)), emp(71L, 100L, 20L)));

        var detail = service.miniappDetail(LEARNER, 5L);

        assertThat(detail.id()).isEqualTo(5L);
    }

    // ---- fixtures ----

    private static Employee emp(long id, long enterpriseId, long companyId) {
        Employee e = new Employee();
        e.setId(id);
        e.setEnterpriseId(enterpriseId);
        e.setInsuranceCompanyId(companyId);
        e.setStatus(1);
        return e;
    }

    private static Course publishedCourse(long id, long companyId) {
        Course c = new Course();
        c.setId(id);
        c.setTitle("课程");
        c.setStatus("PUBLISHED");
        c.setInsuranceCompanyId(companyId);
        c.setPublishedVersionNo(1);
        return c;
    }

    private static CourseVersion publishedVersion(long id, long courseId) {
        CourseVersion v = new CourseVersion();
        v.setId(id);
        v.setCourseId(courseId);
        v.setVersionNo(1);
        v.setTitle("课程");
        v.setStatus("PUBLISHED");
        return v;
    }

    @SuppressWarnings("unchecked")
    private static <T> com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> emptyPage() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> p =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        p.setRecords(List.of());
        p.setTotal(0);
        return p;
    }
}
