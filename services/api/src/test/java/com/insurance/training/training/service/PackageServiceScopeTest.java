package com.insurance.training.training.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.tenancy.service.InsuranceCompanyService;
import com.insurance.training.training.dto.PackageRequest;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
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

/** 培训包归属与写权限：与课程同款规则，客户端不能伪造归属，也不能写他企业的包。 */
class PackageServiceScopeTest {

    private final TrainingPackageMapper packageMapper = Mockito.mock(TrainingPackageMapper.class);
    private final TrainingPackageVersionMapper versionMapper = Mockito.mock(TrainingPackageVersionMapper.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final PackageService service =
            new PackageService(packageMapper, versionMapper, companyService, auditService, 200);

    private static final CurrentUser ENTERPRISE_ADMIN = new CurrentUser(2L, PrincipalType.ADMIN,
            "ENTERPRISE_ADMIN", DataScopeType.ENTERPRISE, 10L, 100L, "企业管理员");
    private static final CurrentUser COMPANY_ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");
    private static final CurrentUser SUPER_ADMIN = new CurrentUser(1L, PrincipalType.ADMIN,
            "SUPER_ADMIN", DataScopeType.ALL, null, null, "平台超管");

    @BeforeEach
    void bindContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void stubInsertAssigningId(long id) {
        Mockito.when(packageMapper.insert(Mockito.any(TrainingPackage.class))).thenAnswer(inv -> {
            TrainingPackage p = inv.getArgument(0);
            p.setId(id);
            return 1;
        });
    }

    @Test
    @DisplayName("企业管理员建包：归属被强制为本企业，请求体无法伪造")
    void enterpriseAdminOwnershipIsForced() {
        CurrentUserHolder.set(ENTERPRISE_ADMIN);
        stubInsertAssigningId(1L);

        service.create(ENTERPRISE_ADMIN, new PackageRequest("新员工入职培训", "简介", null), "127.0.0.1");

        ArgumentCaptor<TrainingPackage> captor = ArgumentCaptor.forClass(TrainingPackage.class);
        Mockito.verify(packageMapper).insert(captor.capture());
        TrainingPackage saved = captor.getValue();
        assertThat(saved.getOwnerEnterpriseId()).isEqualTo(100L);
        assertThat(saved.getInsuranceCompanyId()).isEqualTo(10L);
        assertThat(saved.getLatestVersionNo()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getPublishedVersionNo()).isNull();
    }

    @Test
    @DisplayName("建包同事务创建 v1 草稿，标题与包一致")
    void createAlsoCreatesFirstDraft() {
        CurrentUserHolder.set(ENTERPRISE_ADMIN);
        stubInsertAssigningId(9L);

        service.create(ENTERPRISE_ADMIN, new PackageRequest("消防基础", null, null), "127.0.0.1");

        ArgumentCaptor<TrainingPackageVersion> captor = ArgumentCaptor.forClass(TrainingPackageVersion.class);
        Mockito.verify(versionMapper).insert(captor.capture());
        TrainingPackageVersion draft = captor.getValue();
        assertThat(draft.getPackageId()).isEqualTo(9L);
        assertThat(draft.getVersionNo()).isEqualTo(1);
        assertThat(draft.getTitle()).isEqualTo("消防基础");
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("公司管理员建包为公司级（owner 为 null）")
    void companyAdminCreatesCompanyLevelPackage() {
        CurrentUserHolder.set(COMPANY_ADMIN);
        stubInsertAssigningId(2L);

        service.create(COMPANY_ADMIN, new PackageRequest("公司统一培训", null, null), "127.0.0.1");

        ArgumentCaptor<TrainingPackage> captor = ArgumentCaptor.forClass(TrainingPackage.class);
        Mockito.verify(packageMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerEnterpriseId()).isNull();
    }

    @Test
    @DisplayName("超级管理员可为指定公司建包，且校验公司存在")
    void superAdminCanCreateForAnyCompany() {
        CurrentUserHolder.set(SUPER_ADMIN);
        stubInsertAssigningId(11L);

        service.create(SUPER_ADMIN, new PackageRequest("平台统一培训", null, 20L), "127.0.0.1");

        ArgumentCaptor<TrainingPackage> captor = ArgumentCaptor.forClass(TrainingPackage.class);
        Mockito.verify(packageMapper).insert(captor.capture());
        assertThat(captor.getValue().getInsuranceCompanyId()).isEqualTo(20L);
        assertThat(captor.getValue().getOwnerEnterpriseId()).isNull();
        Mockito.verify(companyService).requireExists(20L);
    }

    @Test
    @DisplayName("超级管理员未指定公司时明确失败，不静默落到任意公司")
    void superAdminWithoutCompanyFails() {
        CurrentUserHolder.set(SUPER_ADMIN);

        assertThatThrownBy(() -> service.create(SUPER_ADMIN, new PackageRequest("x", null, null), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        Mockito.verifyNoInteractions(packageMapper);
    }

    @Test
    @DisplayName("非超管请求体里的 insuranceCompanyId 被忽略，固定为本公司")
    void nonSuperAdminCannotChooseCompany() {
        CurrentUserHolder.set(COMPANY_ADMIN);
        stubInsertAssigningId(3L);

        service.create(COMPANY_ADMIN, new PackageRequest("伪装跨公司", null, 99L), "127.0.0.1");

        ArgumentCaptor<TrainingPackage> captor = ArgumentCaptor.forClass(TrainingPackage.class);
        Mockito.verify(packageMapper).insert(captor.capture());
        assertThat(captor.getValue().getInsuranceCompanyId()).isEqualTo(10L);
        Mockito.verifyNoInteractions(companyService);
    }

    @Test
    @DisplayName("企业管理员写他企业归属的包 → 403")
    void enterpriseAdminCannotWriteOtherEnterprisePackage() {
        Mockito.when(packageMapper.selectById(5L)).thenReturn(packageOf(5L, 10L, 200L));

        assertThatThrownBy(() -> service.getWritable(ENTERPRISE_ADMIN, 5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("企业管理员可读同公司他企业的包（可见但只读）")
    void enterpriseAdminCanReadOtherEnterprisePackage() {
        Mockito.when(packageMapper.selectById(6L)).thenReturn(packageOf(6L, 10L, 200L));

        assertThat(service.getVisible(ENTERPRISE_ADMIN, 6L).getId()).isEqualTo(6L);
    }

    @Test
    @DisplayName("跨集团访问包 → 403")
    void crossCompanyIsForbidden() {
        Mockito.when(packageMapper.selectById(7L)).thenReturn(packageOf(7L, 99L, null));

        assertThatThrownBy(() -> service.getVisible(COMPANY_ADMIN, 7L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("公司级包对企业管理员只读")
    void companyLevelPackageReadOnlyForEnterpriseAdmin() {
        Mockito.when(packageMapper.selectById(8L)).thenReturn(packageOf(8L, 10L, null));

        assertThatThrownBy(() -> service.getWritable(ENTERPRISE_ADMIN, 8L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("包不存在 → 404")
    void missingPackageIsNotFound() {
        Mockito.when(packageMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getVisible(COMPANY_ADMIN, 404L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("学员身份调管理端建包 → 403")
    void employeeCannotCreatePackage() {
        CurrentUser employee = new CurrentUser(7L, PrincipalType.EMPLOYEE, null,
                DataScopeType.ENTERPRISE, 10L, 100L, "学员");
        CurrentUserHolder.set(employee);

        assertThatThrownBy(() -> service.create(employee, new PackageRequest("x", null, null), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verifyNoInteractions(packageMapper);
    }

    private static TrainingPackage packageOf(long id, long companyId, Long ownerEnterpriseId) {
        TrainingPackage p = new TrainingPackage();
        p.setId(id);
        p.setInsuranceCompanyId(companyId);
        p.setOwnerEnterpriseId(ownerEnterpriseId);
        p.setTitle("包" + id);
        p.setStatus("DRAFT");
        p.setLatestVersionNo(1);
        return p;
    }
}