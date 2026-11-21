package com.insurance.training.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;
import com.insurance.training.common.security.CurrentUser;
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
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import org.springframework.stereotype.Service;

/**
 * 学员资源访问授权链。
 *
 * <p>每次请求都重新查库，**不信任 JWT 中的状态快照**：员工被停用、企业被停用、
 * 课程被下线，都必须在下一次请求立即生效。
 *
 * <p>Task 03 固定取「最新已发布版本」；Task 04 引入任务后改为由 assignmentId 决定版本。
 */
@Service
public class MiniappResourceAccessChecker {

    private final LearnerGuard learnerGuard;
    private final CourseMapper courseMapper;
    private final CourseVersionMapper versionMapper;
    private final CourseSectionMapper sectionMapper;
    private final CourseResourceMapper resourceMapper;
    private final CourseVersionResourceMapper bindingMapper;

    public MiniappResourceAccessChecker(LearnerGuard learnerGuard,
                                        CourseMapper courseMapper, CourseVersionMapper versionMapper,
                                        CourseSectionMapper sectionMapper, CourseResourceMapper resourceMapper,
                                        CourseVersionResourceMapper bindingMapper) {
        this.learnerGuard = learnerGuard;
        this.courseMapper = courseMapper;
        this.versionMapper = versionMapper;
        this.sectionMapper = sectionMapper;
        this.resourceMapper = resourceMapper;
        this.bindingMapper = bindingMapper;
    }

    /**
     * 管理端读取资源：校验资源存在、ACTIVE，且课程归属在用户可见范围内。
     * 不要求课程已发布（管理员需要预览草稿内容）。
     */
    public CourseResource requireAdminResource(CurrentUser user, long resourceId) {
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        Course course = courseMapper.selectById(resource.getCourseId());
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        if (!user.isSuperAdmin()
                && (user.companyId() == null || !user.companyId().equals(course.getInsuranceCompanyId()))) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的资源");
        }
        return resource;
    }

    /**
     * 返回已通过全部校验的资源；任一不满足即抛错。
     *
     * <p>**不能用 {@code user.id()} 查 employee**：JWT 的 sub 是 learner_account.id，
     * 与 employee.id 是两套自增序列。混用会对存量学员误判 403，ID 碰撞时更会按
     * 无关员工的公司归属授权（可签名下载他人公司资源）。统一走 {@link LearnerGuard}。
     */
    public CourseResource requireAccess(CurrentUser user, long courseId, long resourceId) {
        // 1+2. 账号存在、名下至少一个启用员工（LearnerGuard 已校验员工与企业启用状态）
        LearnerContext learner = learnerGuard.requireLearner(user);

        // 3. 课程存在、未删除、已发布
        Course course = courseMapper.selectById(courseId);
        if (course == null || !"PUBLISHED".equals(course.getStatus())) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在或未开放");
        }
        // 4. 该学员名下任一员工属于此集团
        Long companyId = course.getInsuranceCompanyId();
        boolean sameCompany = learner.employees().stream()
                .anyMatch(e -> companyId != null && companyId.equals(e.getInsuranceCompanyId()));
        if (!sameCompany) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的课程");
        }
        // 5. 已发布版本存在
        if (course.getPublishedVersionNo() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程尚未发布");
        }
        CourseVersion version = versionMapper.selectOne(Wrappers.<CourseVersion>lambdaQuery()
                .eq(CourseVersion::getCourseId, courseId)
                .eq(CourseVersion::getVersionNo, course.getPublishedVersionNo()));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程版本不存在");
        }
        // 6. 存在绑定行（版本 + 资源）
        CourseVersionResource binding = bindingMapper.selectOne(Wrappers.<CourseVersionResource>lambdaQuery()
                .eq(CourseVersionResource::getCourseVersionId, version.getId())
                .eq(CourseVersionResource::getResourceId, resourceId));
        if (binding == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不属于该课程版本");
        }
        // 7. 绑定行的章节属于同一版本
        CourseSection section = sectionMapper.selectById(binding.getSectionId());
        if (section == null || !version.getId().equals(section.getCourseVersionId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源绑定关系异常");
        }
        // 8. 资源存在、属于该课程、处于 ACTIVE
        CourseResource resource = resourceMapper.selectById(resourceId);
        if (resource == null || !"ACTIVE".equals(resource.getStatus())
                || !resource.getCourseId().equals(courseId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        return resource;
    }
}
