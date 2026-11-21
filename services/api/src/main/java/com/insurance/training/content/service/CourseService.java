package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.insurance.training.audit.service.AuditService;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.common.security.DataScopeType;
import com.insurance.training.common.web.PageQuery;
import com.insurance.training.content.dto.CourseRequest;
import com.insurance.training.content.entity.Course;
import com.insurance.training.content.entity.CourseVersion;
import com.insurance.training.content.mapper.CourseMapper;
import com.insurance.training.content.mapper.CourseVersionMapper;
import com.insurance.training.content.vo.CourseVo;
import com.insurance.training.tenancy.service.InsuranceCompanyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 课程基本信息的读写。 */
@Service
public class CourseService {

    private final CourseMapper courseMapper;
    private final CourseVersionMapper versionMapper;
    private final InsuranceCompanyService companyService;
    private final AuditService auditService;
    private final int maxPageSize;

    public CourseService(CourseMapper courseMapper, CourseVersionMapper versionMapper,
                         InsuranceCompanyService companyService, AuditService auditService,
                         @Value("${app.page.max-size:200}") int maxPageSize) {
        this.courseMapper = courseMapper;
        this.versionMapper = versionMapper;
        this.companyService = companyService;
        this.auditService = auditService;
        this.maxPageSize = maxPageSize;
    }

    /** 列表：按可见范围过滤（同公司共享；企业管理员也能看到同公司其他企业的课程，只读）。 */
    public PageResult<CourseVo> page(CurrentUser user, String keyword, String status,
                                     Long page, Long pageSize) {
        PageQuery pq = PageQuery.of(page, pageSize, maxPageSize);
        var wrapper = Wrappers.<Course>lambdaQuery().orderByDesc(Course::getId);

        if (user.isSuperAdmin()) {
            // 全平台
        }
        else {
            if (user.companyId() == null) {
                return PageResult.empty(pq.page(), pq.pageSize());
            }
            wrapper.eq(Course::getInsuranceCompanyId, user.companyId());
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(Course::getTitle, keyword.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Course::getStatus, status);
        }

        Page<Course> result = courseMapper.selectPage(new Page<>(pq.page(), pq.pageSize()), wrapper);
        List<CourseVo> items = result.getRecords().stream().map(CourseVo::of).toList();
        return PageResult.of(items, result.getTotal(), pq.page(), pq.pageSize());
    }

    /** 读取课程并校验「可见」（同公司）。不存在与无权分别返回 404 / 403。 */
    public Course getVisible(CurrentUser user, long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        if (!user.isSuperAdmin()) {
            if (user.companyId() == null || !user.companyId().equals(course.getInsuranceCompanyId())) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的课程");
            }
        }
        return course;
    }

    /** 读取课程并校验「可写」。 */
    public Course getWritable(CurrentUser user, long courseId) {
        Course course = getVisible(user, courseId);
        DataScopeGuard.checkCourseWrite(user, course.getInsuranceCompanyId(), course.getOwnerEnterpriseId());
        return course;
    }

    /**
     * 新建课程：同事务创建 v1 草稿。
     *
     * <p>归属规则：企业管理员强制归本企业；超管/公司管理员为公司级（owner=null）。
     * 客户端无法通过请求体影响归属。
     */
    @Transactional
    public CourseVo create(CurrentUser user, CourseRequest request, String ip) {
        DataScopeGuard.requireAdmin();
        Long companyId = resolveCompanyId(user, request.insuranceCompanyId());
        Long ownerEnterpriseId = resolveOwnerEnterprise(user);

        Course course = new Course();
        course.setInsuranceCompanyId(companyId);
        course.setOwnerEnterpriseId(ownerEnterpriseId);
        course.setTitle(request.title().trim());
        course.setDescription(blankToNull(request.description()));
        course.setStatus("DRAFT");
        course.setLatestVersionNo(1);
        course.setCreatedByAdminId(user.id());
        courseMapper.insert(course);

        CourseVersion v1 = new CourseVersion();
        v1.setCourseId(course.getId());
        v1.setVersionNo(1);
        v1.setTitle(course.getTitle());
        v1.setDescription(course.getDescription());
        v1.setStatus("DRAFT");
        try {
            versionMapper.insert(v1);
        }
        catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.CONFLICT, "版本号冲突，请重试");
        }

        auditService.record(user, "COURSE_CREATED", "course", course.getId(),
                "companyId=" + companyId + ",ownerEnterpriseId=" + ownerEnterpriseId, ip);
        return CourseVo.of(course);
    }

    /**
     * 修改课程基本信息。
     *
     * <p>标题是列表权威来源；若存在当前 DRAFT 版本，同步其标题（已发布版本快照不改）。
     */
    @Transactional
    public CourseVo update(CurrentUser user, long courseId, CourseRequest request, String ip) {
        Course course = getWritable(user, courseId);
        course.setTitle(request.title().trim());
        course.setDescription(blankToNull(request.description()));
        courseMapper.updateById(course);

        CourseVersion draft = versionMapper.selectOne(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .eq(CourseVersion::getStatus, "DRAFT")
                .orderByDesc(CourseVersion::getVersionNo)
                .last("limit 1"));
        if (draft != null) {
            // 条件更新：select 与 update 之间若被并发发布，快照必须保持冻结，跳过同步即可
            versionMapper.update(null, Wrappers.<CourseVersion>lambdaUpdate()
                    .set(CourseVersion::getTitle, course.getTitle())
                    .set(CourseVersion::getDescription, course.getDescription())
                    .eq(CourseVersion::getId, draft.getId())
                    .eq(CourseVersion::getStatus, "DRAFT"));
        }

        auditService.record(user, "COURSE_UPDATED", "course", courseId, null, ip);
        return CourseVo.of(course);
    }

    /** 逻辑删除；存在已发布版本时拒绝（保护历史引用）。 */
    @Transactional
    public void delete(CurrentUser user, long courseId, String ip) {
        Course course = getWritable(user, courseId);
        Long published = versionMapper.selectCount(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .eq(CourseVersion::getStatus, "PUBLISHED"));
        if (published != null && published > 0) {
            throw new BizException(ErrorCode.CONFLICT, "课程存在已发布版本，不能删除；请先下线并确认无任务引用");
        }
        courseMapper.deleteById(courseId);
        auditService.record(user, "COURSE_DELETED", "course", courseId, null, ip);
    }

    /** 下线：PUBLISHED → OFFLINE，学员不可见；不改变版本内容。 */
    @Transactional
    public CourseVo offline(CurrentUser user, long courseId, String ip) {
        Course course = getWritable(user, courseId);
        if (!"PUBLISHED".equals(course.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有已发布课程可下线");
        }
        course.setStatus("OFFLINE");
        courseMapper.updateById(course);
        auditService.record(user, "COURSE_OFFLINE", "course", courseId, null, ip);
        return CourseVo.of(course);
    }

    /** 恢复上线：要求存在已发布版本。 */
    @Transactional
    public CourseVo online(CurrentUser user, long courseId, String ip) {
        Course course = getWritable(user, courseId);
        if (!"OFFLINE".equals(course.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "只有已下线课程可恢复上线");
        }
        if (course.getPublishedVersionNo() == null) {
            throw new BizException(ErrorCode.CONFLICT, "课程从未发布，无法上线");
        }
        course.setStatus("PUBLISHED");
        courseMapper.updateById(course);
        auditService.record(user, "COURSE_ONLINE", "course", courseId, null, ip);
        return CourseVo.of(course);
    }

    /**
     * 解析课程归属公司。
     *
     * <p>超级管理员可显式指定任意公司（用于平台统一制课）；若未指定则退回其自身公司归属。
     * 其他角色固定为本公司，请求体中的值一律忽略（防止越权建课到他人公司）。
     */
    private Long resolveCompanyId(CurrentUser user, Long requestedCompanyId) {
        if (user.isSuperAdmin()) {
            Long target = requestedCompanyId != null ? requestedCompanyId : user.companyId();
            if (target == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "超级管理员建课需指定集团（insuranceCompanyId）");
            }
            companyService.requireExists(target);
            return target;
        }
        if (user.companyId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前账号未绑定集团，无法建课");
        }
        return user.companyId();
    }

    private Long resolveOwnerEnterprise(CurrentUser user) {
        if (user.scope() == DataScopeType.ENTERPRISE) {
            if (user.enterpriseId() == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "企业管理员账号未绑定企业，无法建课");
            }
            return user.enterpriseId();
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
