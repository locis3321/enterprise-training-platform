package com.insurance.training.training.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 培训包列表与基本信息修改。
 *
 * <p>断言真实生成的 SQL 条件（而非只断言 mock 被调用），以证明租户过滤与草稿同步的条件更新确实落到语句上。
 */
class PackageServiceCrudTest {

    private static final String IP = "127.0.0.1";
    private static final CurrentUser COMPANY_ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");
    private static final CurrentUser SUPER_ADMIN = new CurrentUser(1L, PrincipalType.ADMIN,
            "SUPER_ADMIN", DataScopeType.ALL, null, null, "平台超管");

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
        TableInfoHelper.initTableInfo(assistant, TrainingPackageVersion.class);
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

    // ---------- 列表 ----------

    @Test
    @DisplayName("公司管理员列表：SQL 带 insurance_company_id 过滤，租户隔离由后端落实")
    void pageFiltersByCompany() {
        givenPage(List.of(packageOf(1L)));

        PageResult<PackageVo> result = service.page(COMPANY_ADMIN, null, null, 1L, 10L);

        assertThat(result.items()).hasSize(1);
        assertThat(result.total()).isEqualTo(1);
        LambdaQueryWrapper<TrainingPackage> wrapper = capturePageQuery();
        assertThat(wrapper.getSqlSegment()).contains("insurance_company_id");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(10L);
    }

    @Test
    @DisplayName("超管列表不加公司过滤，可跨公司查看")
    void superAdminPageHasNoCompanyFilter() {
        CurrentUserHolder.set(SUPER_ADMIN);
        givenPage(List.of(packageOf(1L), packageOf(2L)));

        PageResult<PackageVo> result = service.page(SUPER_ADMIN, null, null, 1L, 10L);

        assertThat(result.items()).hasSize(2);
        assertThat(capturePageQuery().getSqlSegment()).doesNotContain("insurance_company_id");
    }

    @Test
    @DisplayName("账号未绑定集团：返回真实空页，不查库也不泄露全平台数据")
    void pageWithoutCompanyReturnsEmpty() {
        CurrentUser noCompany = new CurrentUser(4L, PrincipalType.ADMIN, "INSURANCE_ADMIN",
                DataScopeType.INSURANCE_COMPANY, null, null, "未绑定");
        CurrentUserHolder.set(noCompany);

        PageResult<PackageVo> result = service.page(noCompany, null, null, 1L, 10L);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        Mockito.verifyNoInteractions(packageMapper);
    }

    @Test
    @DisplayName("关键字与状态过滤落到 SQL；关键字两端空白被裁剪")
    void pageAppliesKeywordAndStatus() {
        givenPage(List.of());

        service.page(COMPANY_ADMIN, "  消防  ", "PUBLISHED", 1L, 10L);

        LambdaQueryWrapper<TrainingPackage> wrapper = capturePageQuery();
        assertThat(wrapper.getSqlSegment()).contains("title").contains("status");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("%消防%").containsValue("PUBLISHED");
    }

    @Test
    @DisplayName("pageSize 超上限直接 400，不静默截断")
    void pageSizeOverLimitIsRejected() {
        assertThatThrownBy(() -> service.page(COMPANY_ADMIN, null, null, 1L, 201L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        Mockito.verifyNoInteractions(packageMapper);
    }

    // ---------- 修改基本信息 ----------

    @Test
    @DisplayName("改包标题同步最高草稿：条件更新带 status=DRAFT，不用 updateById 覆盖快照")
    void updateSyncsHighestDraftConditionally() {
        givenWritablePackage(1L, "旧标题");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(versionOf(11L, 1L, 2, "DRAFT"));

        PackageVo vo = service.update(COMPANY_ADMIN, 1L, new PackageRequest("新标题", "新简介", null), IP);

        assertThat(vo.title()).isEqualTo("新标题");
        LambdaUpdateWrapper<TrainingPackageVersion> wrapper = captureVersionUpdate();
        assertThat(wrapper.getSqlSegment()).contains("status");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("DRAFT");
        assertThat(wrapper.getSqlSet()).contains("title");
        Mockito.verify(versionMapper, Mockito.never()).updateById(Mockito.any(TrainingPackageVersion.class));
    }

    @Test
    @DisplayName("草稿在同步前被并发发布：跳过同步，包改名仍成功（包标题是列表权威来源）")
    void updateSkipsSyncWhenDraftPublishedConcurrently() {
        givenWritablePackage(1L, "旧标题");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(versionOf(11L, 1L, 2, "DRAFT"));
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(0);

        PackageVo vo = service.update(COMPANY_ADMIN, 1L, new PackageRequest("新标题", null, null), IP);

        assertThat(vo.title()).isEqualTo("新标题");
        Mockito.verify(packageMapper).updateById(Mockito.any(TrainingPackage.class));
    }

    @Test
    @DisplayName("没有草稿版本时不触碰版本表")
    void updateWithoutDraftDoesNotTouchVersions() {
        givenWritablePackage(1L, "旧标题");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any())).thenReturn(null);

        service.update(COMPANY_ADMIN, 1L, new PackageRequest("新标题", null, null), IP);

        Mockito.verify(versionMapper, Mockito.never()).update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any());
        Mockito.verify(versionMapper, Mockito.never()).updateById(Mockito.any(TrainingPackageVersion.class));
    }

    @Test
    @DisplayName("空白简介归一为 null，避免存入空串")
    void blankDescriptionBecomesNull() {
        givenWritablePackage(1L, "标题");
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any())).thenReturn(null);

        PackageVo vo = service.update(COMPANY_ADMIN, 1L, new PackageRequest("  标题  ", "   ", null), IP);

        assertThat(vo.title()).isEqualTo("标题");
        assertThat(vo.description()).isNull();
    }

    // ---------- fixtures ----------

    private void givenPage(List<TrainingPackage> records) {
        Page<TrainingPackage> page = new Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(records.size());
        Mockito.doReturn(page).when(packageMapper).selectPage(Mockito.any(), Mockito.any());
    }

    private void givenWritablePackage(long id, String title) {
        TrainingPackage p = packageOf(id);
        p.setTitle(title);
        Mockito.when(packageMapper.selectById(id)).thenReturn(p);
    }

    private static TrainingPackage packageOf(long id) {
        TrainingPackage p = new TrainingPackage();
        p.setId(id);
        p.setInsuranceCompanyId(10L);
        p.setOwnerEnterpriseId(null);
        p.setTitle("包" + id);
        p.setStatus("DRAFT");
        p.setLatestVersionNo(1);
        return p;
    }

    private static TrainingPackageVersion versionOf(long id, long packageId, int versionNo, String status) {
        TrainingPackageVersion v = new TrainingPackageVersion();
        v.setId(id);
        v.setPackageId(packageId);
        v.setVersionNo(versionNo);
        v.setTitle("版本标题");
        v.setStatus(status);
        return v;
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<TrainingPackage> capturePageQuery() {
        ArgumentCaptor<LambdaQueryWrapper<TrainingPackage>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        Mockito.verify(packageMapper).selectPage(Mockito.any(), captor.capture());
        LambdaQueryWrapper<TrainingPackage> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<TrainingPackageVersion> captureVersionUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<TrainingPackageVersion>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(versionMapper).update(Mockito.isNull(), captor.capture());
        LambdaUpdateWrapper<TrainingPackageVersion> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        return wrapper;
    }
}