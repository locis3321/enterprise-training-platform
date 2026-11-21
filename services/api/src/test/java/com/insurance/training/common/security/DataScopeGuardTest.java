package com.insurance.training.common.security;

import com.insurance.training.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据范围守卫的跨租户负例：这是 Task 02 的核心安全验收。 */
class DataScopeGuardTest {

    private final CurrentUser insuranceAdmin = new CurrentUser(1L, PrincipalType.ADMIN, "INSURANCE_ADMIN",
            DataScopeType.INSURANCE_COMPANY, 10L, null, "王经理");

    private final CurrentUser enterpriseAdmin = new CurrentUser(2L, PrincipalType.ADMIN, "ENTERPRISE_ADMIN",
            DataScopeType.ENTERPRISE, 10L, 100L, "李主管");

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("集团管理员可访问本集团，访问他集团 403")
    void companyScopeIsolation() {
        assertThatCode(() -> DataScopeGuard.checkCompany(insuranceAdmin, 10L)).doesNotThrowAnyException();

        assertThatThrownBy(() -> DataScopeGuard.checkCompany(insuranceAdmin, 20L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他集团");
    }

    @Test
    @DisplayName("企业管理员只能访问本企业，同公司其他企业 403")
    void enterpriseScopeIsolation() {
        assertThatCode(() -> DataScopeGuard.checkEnterprise(enterpriseAdmin, 10L, 100L))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> DataScopeGuard.checkEnterprise(enterpriseAdmin, 10L, 101L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他企业");

        assertThatThrownBy(() -> DataScopeGuard.checkEnterprise(enterpriseAdmin, 20L, 200L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("列表可见性：跨公司不可见，同公司不同企业按范围判定")
    void canSeeEnterpriseMatchesScope() {
        assertThat(DataScopeGuard.canSeeEnterprise(insuranceAdmin, 10L, 100L)).isTrue();
        assertThat(DataScopeGuard.canSeeEnterprise(insuranceAdmin, 10L, 101L)).isTrue();
        assertThat(DataScopeGuard.canSeeEnterprise(insuranceAdmin, 20L, 200L)).isFalse();
        assertThat(DataScopeGuard.canSeeEnterprise(enterpriseAdmin, 10L, 100L)).isTrue();
        assertThat(DataScopeGuard.canSeeEnterprise(enterpriseAdmin, 10L, 101L)).isFalse();
    }

    @Test
    @DisplayName("非超级管理员不能执行仅超管操作")
    void requireSuperAdminRejectsCompanyAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        CurrentUserHolder.set(insuranceAdmin);

        assertThatThrownBy(DataScopeGuard::requireSuperAdmin)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超级管理员");
    }

    @Test
    @DisplayName("员工主体不能访问管理端接口")
    void employeeCannotActAsAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        CurrentUserHolder.set(new CurrentUser(5L, PrincipalType.EMPLOYEE, null,
                DataScopeType.ENTERPRISE, 10L, 100L, "张三"));

        assertThatThrownBy(DataScopeGuard::requireAdmin)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("管理员");
    }
}
