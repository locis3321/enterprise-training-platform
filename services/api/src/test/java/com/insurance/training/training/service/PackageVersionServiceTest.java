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
import com.insurance.training.training.dto.PackageVersionRequest;
import com.insurance.training.training.entity.PackageCourse;
import com.insurance.training.training.entity.TrainingPackage;
import com.insurance.training.training.entity.TrainingPackageVersion;
import com.insurance.training.training.mapper.PackageCourseMapper;
import com.insurance.training.training.mapper.TrainingPackageMapper;
import com.insurance.training.training.mapper.TrainingPackageVersionMapper;
import com.insurance.training.training.vo.PackageVersionVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 培训包版本生命周期与并发写安全。
 *
 * <p>所有「读-判断-写」路径都必须用条件更新收口；断言真实生成的 SQL，而不是只断言 mock 被调用。
 */
class PackageVersionServiceTest {

    private static final String IP = "127.0.0.1";
    private static final CurrentUser ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    private final TrainingPackageMapper packageMapper = Mockito.mock(TrainingPackageMapper.class);
    private final TrainingPackageVersionMapper versionMapper = Mockito.mock(TrainingPackageVersionMapper.class);
    private final PackageCourseMapper packageCourseMapper = Mockito.mock(PackageCourseMapper.class);
    private final InsuranceCompanyService companyService = Mockito.mock(InsuranceCompanyService.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);

    private final PackageService packageService =
            new PackageService(packageMapper, versionMapper, companyService, auditService, 200);
    private final PackageVersionService service = new PackageVersionService(
            packageService, packageMapper, versionMapper, packageCourseMapper, auditService);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TrainingPackage.class);
        TableInfoHelper.initTableInfo(assistant, TrainingPackageVersion.class);
        TableInfoHelper.initTableInfo(assistant, PackageCourse.class);
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

    // ---------- 版本读取 ----------

    @Test
    @DisplayName("版本号不存在 → 404，不回落最新版本")
    void missingVersionIsNotFound() {
        givenPackage(1L, 3, null);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any())).thenReturn(null);

        assertThatThrownBy(() -> service.getOwnedVersion(1L, 99))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("已发布版本不可修改 → 409")
    void publishedVersionIsNotWritable() {
        givenPackage(1L, 1, 1);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "PUBLISHED"));

        assertThatThrownBy(() -> service.getWritableDraft(ADMIN, 1L, 1))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("版本列表带每个版本的课程数")
    void listCarriesCourseCounts() {
        givenPackage(1L, 2, 1);
        Mockito.when(versionMapper.selectList(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(List.of(version(12L, 1L, 2, "DRAFT"), version(11L, 1L, 1, "PUBLISHED")));
        Mockito.when(packageCourseMapper.selectList(Mockito.<Wrapper<PackageCourse>>any()))
                .thenReturn(List.of(course(1L, 11L, 90L, 1), course(2L, 11L, 91L, 2), course(3L, 12L, 90L, 1)));

        List<PackageVersionVo> versions = service.list(ADMIN, 1L);

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(2);
        assertThat(versions.get(0).courseCount()).isEqualTo(1);
        assertThat(versions.get(1).courseCount()).isEqualTo(2);
    }

    // ---------- 新建草稿版本 ----------

    @Test
    @DisplayName("新建版本：latest_version_no 条件递增，SQL 同时带旧值与新值")
    void createBumpsVersionNoConditionally() {
        givenPackage(1L, 1, 1);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(1);
        Mockito.when(versionMapper.insert(Mockito.any(TrainingPackageVersion.class))).thenAnswer(inv -> {
            TrainingPackageVersion v = inv.getArgument(0);
            v.setId(12L);
            return 1;
        });

        PackageVersionVo vo = service.create(ADMIN, 1L, null, IP);

        assertThat(vo.versionNo()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(vo.title()).isEqualTo("包标题");
        LambdaUpdateWrapper<TrainingPackage> wrapper = capturePackageUpdate();
        assertThat(wrapper.getSqlSegment()).contains("latest_version_no");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(1).containsValue(2);
    }

    @Test
    @DisplayName("版本号 CAS 失败 → 409，且不插入版本行")
    void createConflictsWhenVersionNoCasLoses() {
        givenPackage(1L, 1, null);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(0);

        assertThatThrownBy(() -> service.create(ADMIN, 1L, null, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).insert(Mockito.any(TrainingPackageVersion.class));
    }

    @Test
    @DisplayName("唯一键冲突（并发抢同一版本号）→ 409")
    void createConflictsOnDuplicateVersionNo() {
        givenPackage(1L, 1, null);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(1);
        Mockito.when(versionMapper.insert(Mockito.any(TrainingPackageVersion.class)))
                .thenThrow(new DuplicateKeyException("uk_package_version_no"));

        assertThatThrownBy(() -> service.create(ADMIN, 1L, null, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("latest_version_no 缺失 → 500 数据异常，不猜一个版本号")
    void missingLatestVersionNoFailsExplicitly() {
        TrainingPackage pkg = givenPackage(1L, 1, null);
        pkg.setLatestVersionNo(null);

        assertThatThrownBy(() -> service.create(ADMIN, 1L, null, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        Mockito.verifyNoInteractions(versionMapper);
    }

    // ---------- 修改草稿 ----------

    @Test
    @DisplayName("改草稿标题走条件更新，SQL 带 status=DRAFT")
    void updateDraftIsConditional() {
        givenPackage(1L, 1, null);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "DRAFT"));
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(1);

        PackageVersionVo vo = service.update(ADMIN, 1L, 1, new PackageVersionRequest("新标题", "新简介"), IP);

        assertThat(vo.title()).isEqualTo("新标题");
        LambdaUpdateWrapper<TrainingPackageVersion> wrapper = captureVersionUpdate();
        assertThat(wrapper.getSqlSegment()).contains("status");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("DRAFT");
    }

    @Test
    @DisplayName("草稿在修改前被并发发布：CAS 影响 0 行 → 409，不覆盖快照")
    void updateConflictsWhenPublishedConcurrently() {
        givenPackage(1L, 1, null);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "DRAFT"));
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.update(ADMIN, 1L, 1, new PackageVersionRequest("新标题", null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------- 发布 ----------

    @Test
    @DisplayName("没有任何课程 → 409，且不做任何写操作")
    void publishRejectsEmptyPackage() {
        givenPackage(1L, 1, null);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "DRAFT"));
        Mockito.when(packageCourseMapper.selectCount(Mockito.<Wrapper<PackageCourse>>any())).thenReturn(0L);

        assertThatThrownBy(() -> service.publish(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any());
        Mockito.verify(packageMapper, Mockito.never()).update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any());
    }

    @Test
    @DisplayName("重复发布已发布版本：在任何写操作前就 409")
    void publishRejectsAlreadyPublished() {
        givenPackage(1L, 1, 1);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "PUBLISHED"));

        assertThatThrownBy(() -> service.publish(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any());
        Mockito.verifyNoInteractions(packageCourseMapper);
    }

    @Test
    @DisplayName("不能发布早于当前生效版本的历史版本 → 409（回滚须走复制）")
    void publishRejectsOlderVersion() {
        givenPackage(1L, 3, 2);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "DRAFT"));

        assertThatThrownBy(() -> service.publish(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any());
    }

    @Test
    @DisplayName("版本状态 CAS 失败 → 409，且绝不推进包指针")
    void publishConflictsWhenVersionCasLoses() {
        givenPublishable(1L, 1, null);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.publish(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(packageMapper, Mockito.never()).update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any());
    }

    @Test
    @DisplayName("包指针 CAS 失败 → 409（事务回滚，版本状态不会留在 PUBLISHED）")
    void publishConflictsWhenPackagePointerCasLoses() {
        givenPublishable(1L, 1, null);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(1);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(0);

        assertThatThrownBy(() -> service.publish(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("发布成功：版本 CAS 带 DRAFT，包指针 CAS 带 published_version_no 不回退条件")
    void publishSucceedsWhenBothCasWin() {
        givenPublishable(1L, 2, 1);
        Mockito.when(versionMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(1);
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(1);

        PackageVersionVo vo = service.publish(ADMIN, 1L, 2, IP);

        assertThat(vo.status()).isEqualTo("PUBLISHED");
        assertThat(vo.courseCount()).isEqualTo(2);
        assertThat(captureVersionUpdate().getParamNameValuePairs()).containsValue("DRAFT");
        LambdaUpdateWrapper<TrainingPackage> wrapper = capturePackageUpdate();
        assertThat(wrapper.getSqlSegment()).contains("published_version_no");
        assertThat(wrapper.getSqlSet()).contains("published_version_no");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(2);
        Mockito.verify(auditService).record(Mockito.eq(ADMIN), Mockito.eq("PACKAGE_VERSION_PUBLISHED"),
                Mockito.eq("training_package_version"), Mockito.eq(11L), Mockito.anyString(), Mockito.eq(IP));
    }

    // ---------- 复制 ----------

    @Test
    @DisplayName("复制版本：深复制 package_course 行，指向同一课程版本，新版本为 DRAFT")
    void copyDeepCopiesCourseLinks() {
        givenPackage(1L, 1, 1);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "PUBLISHED"));
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(1);
        Mockito.when(versionMapper.insert(Mockito.any(TrainingPackageVersion.class))).thenAnswer(inv -> {
            TrainingPackageVersion v = inv.getArgument(0);
            v.setId(12L);
            return 1;
        });
        Mockito.when(packageCourseMapper.selectList(Mockito.<Wrapper<PackageCourse>>any()))
                .thenReturn(List.of(course(1L, 11L, 90L, 1), course(2L, 11L, 91L, 2)));

        PackageVersionVo vo = service.copy(ADMIN, 1L, 1, IP);

        assertThat(vo.versionNo()).isEqualTo(2);
        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(vo.courseCount()).isEqualTo(2);
        ArgumentCaptor<PackageCourse> captor = ArgumentCaptor.forClass(PackageCourse.class);
        Mockito.verify(packageCourseMapper, Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(link -> {
            assertThat(link.getPackageVersionId()).isEqualTo(12L);
            assertThat(link.getId()).isNull();
        });
        assertThat(captor.getAllValues()).extracting(PackageCourse::getCourseVersionId)
                .containsExactly(90L, 91L);
        assertThat(captor.getAllValues()).extracting(PackageCourse::getSortNo).containsExactly(1, 2);
    }

    @Test
    @DisplayName("复制时版本号 CAS 失败 → 409，不插入任何行")
    void copyConflictsWhenVersionNoCasLoses() {
        givenPackage(1L, 1, 1);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, 1L, 1, "PUBLISHED"));
        Mockito.when(packageMapper.update(Mockito.isNull(), Mockito.<Wrapper<TrainingPackage>>any())).thenReturn(0);

        assertThatThrownBy(() -> service.copy(ADMIN, 1L, 1, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(versionMapper, Mockito.never()).insert(Mockito.any(TrainingPackageVersion.class));
        Mockito.verify(packageCourseMapper, Mockito.never()).insert(Mockito.any(PackageCourse.class));
    }

    // ---------- fixtures ----------

    private TrainingPackage givenPackage(long id, int latestVersionNo, Integer publishedVersionNo) {
        TrainingPackage pkg = new TrainingPackage();
        pkg.setId(id);
        pkg.setInsuranceCompanyId(10L);
        pkg.setOwnerEnterpriseId(null);
        pkg.setTitle("包标题");
        pkg.setStatus(publishedVersionNo == null ? "DRAFT" : "PUBLISHED");
        pkg.setLatestVersionNo(latestVersionNo);
        pkg.setPublishedVersionNo(publishedVersionNo);
        Mockito.when(packageMapper.selectById(id)).thenReturn(pkg);
        return pkg;
    }

    private void givenPublishable(long packageId, int versionNo, Integer publishedVersionNo) {
        givenPackage(packageId, versionNo, publishedVersionNo);
        Mockito.when(versionMapper.selectOne(Mockito.<Wrapper<TrainingPackageVersion>>any()))
                .thenReturn(version(11L, packageId, versionNo, "DRAFT"));
        Mockito.when(packageCourseMapper.selectCount(Mockito.<Wrapper<PackageCourse>>any())).thenReturn(2L);
    }

    private static TrainingPackageVersion version(long id, long packageId, int versionNo, String status) {
        TrainingPackageVersion v = new TrainingPackageVersion();
        v.setId(id);
        v.setPackageId(packageId);
        v.setVersionNo(versionNo);
        v.setTitle("版本标题");
        v.setStatus(status);
        return v;
    }

    private static PackageCourse course(long id, long packageVersionId, long courseVersionId, int sortNo) {
        PackageCourse link = new PackageCourse();
        link.setId(id);
        link.setPackageVersionId(packageVersionId);
        link.setCourseVersionId(courseVersionId);
        link.setSortNo(sortNo);
        return link;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<TrainingPackage> capturePackageUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<TrainingPackage>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(packageMapper).update(Mockito.isNull(), captor.capture());
        LambdaUpdateWrapper<TrainingPackage> wrapper = captor.getValue();
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