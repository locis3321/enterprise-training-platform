package com.insurance.training.training.service;

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
import com.insurance.training.tenancy.service.InsuranceCompanyService;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.vo.PackageVo;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 培训包下线/上线：只有合法状态迁移可执行，且不动已发布版本指针。 */
class PackageLifecycleTest {

    private static final String IP = "127.0.0.1";
    private static final CurrentUser ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    private final TrainingPackageMapper packageMapper = Mockito.mock(TrainingPackageMapper.class);
    private final TrainingPackageVersionMapper versionMapper = Mockito.mock(TrainingPackageVersionMapper.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final PackageService service =
            new PackageService(packageMapper, versionMapper, companyService, auditService, 200);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingPackage.class);
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

    @Test
    @DisplayName("已发布包可下线，状态 CAS 带旧状态 PUBLISHED")
    void offlineMovesPublishedToOffline() {
        givenPackage("PUBLISHED", 1);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(1);

        PackageVo vo = service.offline(ADMIN, 1L, IP);

        assertThat(vo.status()).isEqualTo("OFFLINE");
        LambdaUpdateWrapper<TrainingPackage> w = captureUpdate();
        assertThat(w.getSqlSet()).contains("status");
        assertThat(w.getSqlSegment()).contains("status");
        assertThat(w.getParamNameValuePairs()).containsValue("PUBLISHED");
        Mockito.verify(auditService).record(Mockito.eq(ADMIN), Mockito.eq("PACKAGE_OFFLINE"),
                Mockito.eq("training_package"), Mockito.eq(1L), Mockito.any(), Mockito.eq(IP));
    }

    @Test
    @DisplayName("草稿包不能下线 → 409，且不写库")
    void offlineRejectsDraftPackage() {
        givenPackage("DRAFT", null);

        assertThatThrownBy(() -> service.offline(ADMIN, 1L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any());
    }

    @Test
    @DisplayName("下线时被并发改变状态（CAS 0 行）→ 409")
    void offlineConflictsWhenCasLoses() {
        givenPackage("PUBLISHED", 1);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(0);

        assertThatThrownBy(() -> service.offline(ADMIN, 1L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("已下线包可恢复上线，且不清空已发布版本指针")
    void onlineMovesOfflineToPublished() {
        givenPackage("OFFLINE", 2);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(1);

        PackageVo vo = service.online(ADMIN, 1L, IP);

        assertThat(vo.status()).isEqualTo("PUBLISHED");
        assertThat(vo.publishedVersionNo()).isEqualTo(2);
        LambdaUpdateWrapper<TrainingPackage> w = captureUpdate();
        assertThat(w.getParamNameValuePairs()).containsValue("OFFLINE");
        Mockito.verify(auditService).record(Mockito.eq(ADMIN), Mockito.eq("PACKAGE_ONLINE"),
                Mockito.eq("training_package"), Mockito.eq(1L), Mockito.any(), Mockito.eq(IP));
    }

    @Test
    @DisplayName("从未发布过的包不能上线 → 409（没有可恢复内容）")
    void onlineRejectsNeverPublishedPackage() {
        givenPackage("OFFLINE", null);

        assertThatThrownBy(() -> service.online(ADMIN, 1L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any());
    }

    @Test
    @DisplayName("已在线包不能重复上线 → 409")
    void onlineRejectsPublishedPackage() {
        givenPackage("PUBLISHED", 1);

        assertThatThrownBy(() -> service.online(ADMIN, 1L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any());
    }

    @Test
    @DisplayName("企业管理员下线公司级包 → 403（写权限同 checkCourseWrite）")
    void enterpriseAdminCannotOfflineCompanyLevelPackage() {
        TrainingPackage pkg = givenPackage("PUBLISHED", 1);
        pkg.setOwnerEnterpriseId(null);
        CurrentUser enterpriseAdmin = new CurrentUser(2L, PrincipalType.ADMIN, "ENTERPRISE_ADMIN",
                DataScopeType.ENTERPRISE, 10L, 100L, "企业管理员");

        assertThatThrownBy(() -> service.offline(enterpriseAdmin, 1L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        Mockito.verify(packageMapper, Mockito.never())
                .update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any());
    }

    // ---------- fixtures ----------

    private TrainingPackage givenPackage(String status, Integer publishedVersionNo) {
        TrainingPackage p = new TrainingPackage();
        p.setId(1L);
        p.setInsuranceCompanyId(10L);
        p.setOwnerEnterpriseId(null);
        p.setTitle("培训包");
        p.setStatus(status);
        p.setLatestVersionNo(2);
        p.setPublishedVersionNo(publishedVersionNo);
        Mockito.when(packageMapper.selectById(1L)).thenReturn(p);
        return p;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<TrainingPackage> captureUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<TrainingPackage>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(packageMapper).update(Mockito.isNull(), captor.capture());
        LambdaUpdateWrapper<TrainingPackage> w = captor.getValue();
        w.getSqlSegment();
        return w;
    }
}