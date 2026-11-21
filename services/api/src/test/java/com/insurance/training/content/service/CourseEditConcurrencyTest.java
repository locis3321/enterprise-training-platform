package com.insurance.training.content.service;

import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.security.PrincipalType;
import com.insurance.training.content.dto.SectionRequest;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.entity.CourseVersionResource;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseResourceMapper;
import com.insurance.training.content.mapper.CourseSectionMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.mapper.CourseVersionResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 课程编排写入必须在同一条语句里判定「版本仍为 DRAFT」。
 *
 * <p>先查状态再 insert/update/delete 存在窗口：并发发布会把内容写进已冻结的版本快照，
 * 而培训任务绑定的正是这个快照。
 */
class CourseEditConcurrencyTest {

    private static final String IP = "127.0.0.1";
    private static final CurrentUser ADMIN = new CurrentUser(3L, PrincipalType.ADMIN,
            "INSURANCE_ADMIN", DataScopeType.INSURANCE_COMPANY, 10L, null, "公司管理员");

    private final CourseMapper courseMapper = Mockito.mock(CourseMapper.class);
    private final CourseVersionMapper versionMapper = Mockito.mock(CourseVersionMapper.class);
    private final CourseSectionMapper sectionMapper = Mockito.mock(CourseSectionMapper.class);
    private final CourseResourceMapper resourceMapper = Mockito.mock(CourseResourceMapper.class);
    private final CourseVersionResourceMapper bindingMapper = Mockito.mock(CourseVersionResourceMapper.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);

    private final CourseService courseService =
            new CourseService(courseMapper, versionMapper,
                    Mockito.mock(com.insurance.training.tenancy.service.InsuranceCompanyService.class),
                    auditService, 200);
    private final CourseVersionService versionService = new CourseVersionService(
            courseService, courseMapper, versionMapper, sectionMapper, resourceMapper, bindingMapper, auditService);
    private final CourseSectionService sectionService =
            new CourseSectionService(courseService, versionService, sectionMapper, versionMapper,
                    bindingMapper, auditService);
    private final ResourceBindingService bindingService = new ResourceBindingService(
            courseService, versionService, versionMapper, sectionMapper, resourceMapper, bindingMapper, auditService);

    @BeforeEach
    void bindContext() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        CurrentUserHolder.set(ADMIN);
        givenWritableDraftVersion();
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------- 章节 ----------

    @Test
    @DisplayName("新增章节走条件写入，不是先查后插")
    void createSectionUsesConditionalInsert() {
        Mockito.when(sectionMapper.insertIfDraft(11L, "第一章", 1, 600)).thenReturn(1);
        Mockito.when(sectionMapper.selectOne(Mockito.any())).thenReturn(sectionOf(21L, 11L));

        CourseSection created = sectionService.create(ADMIN, 1L, 1, new SectionRequest("第一章", 1, 600), IP);

        org.assertj.core.api.Assertions.assertThat(created.getId()).isEqualTo(21L);
        Mockito.verify(sectionMapper).insertIfDraft(11L, "第一章", 1, 600);
        Mockito.verify(sectionMapper, Mockito.never()).insert(Mockito.any(CourseSection.class));
    }

    @Test
    @DisplayName("章节在新增前被并发发布：条件写入 0 行 → 409")
    void createSectionConflictsWhenPublishedConcurrently() {
        Mockito.when(sectionMapper.insertIfDraft(11L, "第二章", 2, 300)).thenReturn(0);

        assertThatThrownBy(() -> sectionService.create(ADMIN, 1L, 1, new SectionRequest("第二章", 2, 300), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(auditService, Mockito.never()).record(Mockito.any(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("并发抢同一章节排序号：唯一键冲突转 409")
    void createSectionDuplicateSortNoConflicts() {
        Mockito.when(sectionMapper.insertIfDraft(11L, "第一章", 1, null))
                .thenThrow(new DuplicateKeyException("uk_section_version_sort"));

        assertThatThrownBy(() -> sectionService.create(ADMIN, 1L, 1, new SectionRequest("第一章", 1, null), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("修改章节走条件更新，0 行 → 409")
    void updateSectionConflictsWhenPublishedConcurrently() {
        Mockito.when(sectionMapper.selectById(21L)).thenReturn(sectionOf(21L, 11L));
        Mockito.when(sectionMapper.updateIfDraft(21L, 11L, "改名", 1, 600)).thenReturn(0);

        assertThatThrownBy(() -> sectionService.update(ADMIN, 1L, 1, 21L, new SectionRequest("改名", 1, 600), IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(sectionMapper, Mockito.never()).updateById(Mockito.any(CourseSection.class));
    }

    @Test
    @DisplayName("删除章节走条件删除，0 行且版本已发布 → 409")
    void deleteSectionConflictsWhenPublishedConcurrently() {
        Mockito.when(sectionMapper.selectById(21L)).thenReturn(sectionOf(21L, 11L));
        Mockito.when(bindingMapper.selectCount(Mockito.any())).thenReturn(0L);
        Mockito.when(sectionMapper.deleteIfDraft(21L, 11L)).thenReturn(0);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(versionOf("PUBLISHED"));

        assertThatThrownBy(() -> sectionService.delete(ADMIN, 1L, 1, 21L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(sectionMapper, Mockito.never()).deleteById(Mockito.anyLong());
    }

    @Test
    @DisplayName("删除章节条件写成功时不报错")
    void deleteSectionSucceedsWhenConditionalDeleteHits() {
        Mockito.when(sectionMapper.selectById(21L)).thenReturn(sectionOf(21L, 11L));
        Mockito.when(bindingMapper.selectCount(Mockito.any())).thenReturn(0L);
        Mockito.when(sectionMapper.deleteIfDraft(21L, 11L)).thenReturn(1);

        assertThatCode(() -> sectionService.delete(ADMIN, 1L, 1, 21L, IP)).doesNotThrowAnyException();
    }

    // ---------- 资源绑定 ----------

    @Test
    @DisplayName("绑定资源走条件写入，不是先查后插")
    void bindResourceUsesConditionalInsert() {
        givenSectionAndResource();
        Mockito.when(bindingMapper.selectCount(Mockito.any())).thenReturn(0L);
        Mockito.when(bindingMapper.insertIfDraft(11L, 21L, 31L, 1)).thenReturn(1);

        bindingService.bind(ADMIN, 1L, 1, 21L, 31L, IP);

        Mockito.verify(bindingMapper).insertIfDraft(11L, 21L, 31L, 1);
        Mockito.verify(bindingMapper, Mockito.never()).insert(Mockito.any(CourseVersionResource.class));
    }

    @Test
    @DisplayName("绑定资源时版本被并发发布：0 行 → 409")
    void bindResourceConflictsWhenPublishedConcurrently() {
        givenSectionAndResource();
        Mockito.when(bindingMapper.selectCount(Mockito.any())).thenReturn(0L);
        Mockito.when(bindingMapper.insertIfDraft(11L, 21L, 31L, 1)).thenReturn(0);

        assertThatThrownBy(() -> bindingService.bind(ADMIN, 1L, 1, 21L, 31L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("解绑走条件删除；0 行且版本已发布 → 409 而不是误报 404")
    void unbindResourceConflictsWhenPublishedConcurrently() {
        Mockito.when(bindingMapper.selectOne(Mockito.any()))
                .thenReturn(bindingOf(41L, 11L, 21L, 31L));
        Mockito.when(bindingMapper.deleteIfDraft(11L, 21L, 31L)).thenReturn(0);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(versionOf("PUBLISHED"));

        assertThatThrownBy(() -> bindingService.unbind(ADMIN, 1L, 1, 21L, 31L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        Mockito.verify(bindingMapper, Mockito.never()).deleteById(Mockito.anyLong());
    }

    @Test
    @DisplayName("解绑条件删除 0 行且版本仍为草稿 → 404（绑定不存在）")
    void unbindResourceMissingIsNotFound() {
        Mockito.when(bindingMapper.selectOne(Mockito.any()))
                .thenReturn(bindingOf(41L, 11L, 21L, 31L));
        Mockito.when(bindingMapper.deleteIfDraft(11L, 21L, 31L)).thenReturn(0);
        Mockito.when(versionMapper.selectById(11L)).thenReturn(versionOf("DRAFT"));

        assertThatThrownBy(() -> bindingService.unbind(ADMIN, 1L, 1, 21L, 31L, IP))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------- fixtures ----------

    private void givenWritableDraftVersion() {
        Course course = new Course();
        course.setId(1L);
        course.setInsuranceCompanyId(10L);
        course.setOwnerEnterpriseId(null);
        course.setTitle("课程");
        course.setStatus("DRAFT");
        course.setLatestVersionNo(1);
        Mockito.when(courseMapper.selectById(1L)).thenReturn(course);

        CourseVersion version = versionOf("DRAFT");
        Mockito.when(versionMapper.selectOne(Mockito.any())).thenReturn(version);
    }

    private void givenSectionAndResource() {
        Mockito.when(sectionMapper.selectById(21L)).thenReturn(sectionOf(21L, 11L));
        com.insurance.training.content.entity.CourseResource resource =
                new com.insurance.training.content.entity.CourseResource();
        resource.setId(31L);
        resource.setCourseId(1L);
        resource.setStatus("ACTIVE");
        resource.setKind("PDF");
        Mockito.when(resourceMapper.selectById(31L)).thenReturn(resource);
    }

    private static CourseVersion versionOf(String status) {
        CourseVersion v = new CourseVersion();
        v.setId(11L);
        v.setCourseId(1L);
        v.setVersionNo(1);
        v.setTitle("版本");
        v.setStatus(status);
        return v;
    }

    private static CourseSection sectionOf(long id, long versionId) {
        CourseSection s = new CourseSection();
        s.setId(id);
        s.setCourseVersionId(versionId);
        s.setTitle("章节");
        s.setSortNo(1);
        return s;
    }

    private static CourseVersionResource bindingOf(long id, long versionId, long sectionId, long resourceId) {
        CourseVersionResource b = new CourseVersionResource();
        b.setId(id);
        b.setCourseVersionId(versionId);
        b.setSectionId(sectionId);
        b.setResourceId(resourceId);
        b.setSortNo(1);
        return b;
    }
}