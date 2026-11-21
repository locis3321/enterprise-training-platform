package com.insurance.training.common.security;

import com.insurance.training.common.exception.BizException;
import com.insurance.training.common.response.ErrorCode;

/**
 * 数据范围守卫。
 *
 * <p>所有涉及企业/员工的读写都必须经过这里，避免「某个接口忘了加 where 条件」。
 * 拒绝一律返回 403，不返回 404，以免通过状态码探测其他租户的数据是否存在。
 */
public final class DataScopeGuard {

    private DataScopeGuard() {
    }

    /** 要求当前主体为管理员。 */
    public static CurrentUser requireAdmin() {
        CurrentUser user = CurrentUserHolder.require();
        if (!user.isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该接口仅管理员可访问");
        }
        return user;
    }

    /** 要求当前主体为平台超级管理员。 */
    public static CurrentUser requireSuperAdmin() {
        CurrentUser user = requireAdmin();
        if (!user.isSuperAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "该操作仅平台超级管理员可执行");
        }
        return user;
    }

    /** 校验某集团是否在授权范围内。 */
    public static void checkCompany(CurrentUser user, long companyId) {
        switch (user.scope()) {
            case ALL -> {
            }
            case INSURANCE_COMPANY, ENTERPRISE -> {
                if (user.companyId() == null || user.companyId() != companyId) {
                    throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的数据");
                }
            }
        }
    }

    /** 校验某企业是否在授权范围内。 */
    public static void checkEnterprise(CurrentUser user, long companyId, long enterpriseId) {
        checkCompany(user, companyId);
        if (user.scope() == DataScopeType.ENTERPRISE
                && (user.enterpriseId() == null || user.enterpriseId() != enterpriseId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他企业的数据");
        }
    }

    /**
     * 课程写权限。
     *
     * <p>规则（Task 03 权限矩阵）：
     * <ul>
     *   <li>SUPER_ADMIN：任意课程；</li>
     *   <li>INSURANCE_ADMIN：本公司全部课程；</li>
     *   <li>ENTERPRISE_ADMIN：只能写 ownerEnterpriseId = 本企业的课程；公司级课程
     *       （ownerEnterpriseId 为 null）其只读。</li>
     * </ul>
     * 拒绝统一 403，不区分「不存在」与「无权」，避免探测。
     */
    public static void checkCourseWrite(CurrentUser user, Long companyId, Long ownerEnterpriseId) {
        if (user.isSuperAdmin()) {
            return;
        }
        if (user.companyId() == null || companyId == null || user.companyId().longValue() != companyId) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问其他集团的课程");
        }
        if (user.scope() == DataScopeType.ENTERPRISE) {
            if (ownerEnterpriseId == null || user.enterpriseId() == null
                    || user.enterpriseId().longValue() != ownerEnterpriseId) {
                throw new BizException(ErrorCode.FORBIDDEN, "企业管理员只能维护本企业归属的课程");
            }
        }
    }

    /** 判断企业是否可见（列表过滤用，不抛异常）。 */
    public static boolean canSeeEnterprise(CurrentUser user, long companyId, long enterpriseId) {
        if (user.isSuperAdmin()) {
            return true;
        }
        if (user.companyId() == null || user.companyId() != companyId) {
            return false;
        }
        return user.scope() != DataScopeType.ENTERPRISE
                || (user.enterpriseId() != null && user.enterpriseId() == enterpriseId);
    }

    /**
     * 管理端账号的写入权限（编辑/重置/删除）。
     *
     * <p>逐级自治：平台管理员管全部；集团管理员管本集团（含集团级与下属子公司账号）；
     * 子公司管理员只管本子公司账号。跨集团一律 403。
     *
     * <p>自我保护的「不能操作自己」由 Service 层判定（可发现、可测试），此处只判范围。
     */
    public static void checkAdminUserWrite(CurrentUser actor, Long targetCompanyId, Long targetEnterpriseId) {
        if (actor.isSuperAdmin()) {
            return;
        }
        if (actor.companyId() == null || targetCompanyId == null
                || !actor.companyId().equals(targetCompanyId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权管理其他集团的账号");
        }
        if (actor.scope() == DataScopeType.ENTERPRISE) {
            if (actor.enterpriseId() == null || !actor.enterpriseId().equals(targetEnterpriseId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "子公司管理员只能管理本子公司账号");
            }
        }
    }

    /**
     * 新建管理端账号的权限：在范围校验之外，禁止创建权限高于自己的账号。
     *
     * <p>集团管理员可建集团级与子公司级账号，但不得建平台级；全平台级由平台管理员独有。
     */
    public static void checkAdminUserCreate(CurrentUser actor, String roleCode,
                                            Long companyId, Long enterpriseId) {
        if (actor.isSuperAdmin()) {
            return;
        }
        if (!"INSURANCE_ADMIN".equals(roleCode) && !"ENTERPRISE_ADMIN".equals(roleCode)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权创建该角色的账号");
        }
        if (actor.scope() == DataScopeType.ENTERPRISE) {
            if (!"ENTERPRISE_ADMIN".equals(roleCode)) {
                throw new BizException(ErrorCode.FORBIDDEN, "子公司管理员只能创建子公司管理员账号");
            }
            if (actor.enterpriseId() == null || !actor.enterpriseId().equals(enterpriseId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "只能在本人子公司下建号");
            }
            if (actor.companyId() == null || !actor.companyId().equals(companyId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "只能在本人集团下建号");
            }
            return;
        }
        checkAdminUserWrite(actor, companyId, enterpriseId);
    }
}
