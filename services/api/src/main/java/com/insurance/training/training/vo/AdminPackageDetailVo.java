package com.insurance.training.training.vo;

import java.util.List;

/** 培训包详情（管理端）。可指定版本查看草稿内容；版本号不存在返回 404，不回落最新。 */
public record AdminPackageDetailVo(
        long id,
        long insuranceCompanyId,
        Long ownerEnterpriseId,
        String title,
        String description,
        String status,
        int latestVersionNo,
        Integer publishedVersionNo,
        List<PackageVersionVo> versions,
        DetailVersion version
) {

    /** 指定版本的完整课程清单。 */
    public record DetailVersion(
            long versionId,
            int versionNo,
            String title,
            String description,
            String status,
            List<PackageCourseVo> courses
    ) {
    }
}