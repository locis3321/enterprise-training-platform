package com.insurance.training.tenancy.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.tenancy.dto.EnterpriseRequest;
import com.insurance.training.tenancy.mapper.EnterpriseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 企业创建的范围回归。
 *
 * <p>背景：联调时发现企业管理员可在本公司下新建任意企业（越权扩张组织）。
 * 该用例锁定「企业管理员不能新建企业」这一规则，防止回归。
 */
class EnterpriseServiceScopeTest {

    private final EnterpriseMapper mapper = Mockito.mock(EnterpriseMapper.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final EnterpriseService service = new EnterpriseService(mapper, companyService, auditService, 200);

    private final EnterpriseRequest request = new EnterpriseRequest(1L, "越权企业", "ENT-X", null, null, 1);

    @BeforeEach
    void bindRequestContext() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("企业管理员不能新建企业")
    void enterpriseAdminCannotCreateEnterprise() {
        CurrentUser user = new CurrentUser(2L, PrincipalType.ADMIN, "ENTERPRISE_ADMIN",
                DataScopeType.ENTERPRISE, 1L, 1L, "企业管理员");
        CurrentUserHolder.set(user);

        assertThatThrownBy(() -> service.create(user, request, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("集团管理员不能在他集团下建子公司")
    void companyAdminCannotCreateInOtherCompany() {
        CurrentUser user = new CurrentUser(3L, PrincipalType.ADMIN, "INSURANCE_ADMIN",
                DataScopeType.INSURANCE_COMPANY, 99L, null, "其他公司管理员");
        CurrentUserHolder.set(user);

        assertThatThrownBy(() -> service.create(user, request, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(Mockito.mockingDetails(mapper).getInvocations()).isEmpty();
    }
}
