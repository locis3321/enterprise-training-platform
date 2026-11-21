package com.insurance.training.training.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.training.dto.PackageCourseRequest;
import com.insurance.training.training.dto.PackageRequest;
import com.insurance.training.training.dto.PackageVersionRequest;
import com.insurance.training.training.service.PackageCourseService;
import com.insurance.training.training.service.PackageDetailService;
import com.insurance.training.training.service.PackageService;
import com.insurance.training.training.service.PackageVersionService;
import com.insurance.training.training.vo.AdminPackageDetailVo;
import com.insurance.training.training.vo.PackageVersionVo;
import com.insurance.training.training.vo.PackageVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 培训包与不可变版本管理。任务下发见 T3 的 /admin/training/tasks。 */
@Tag(name = "管理端-培训包")
@RestController
@RequestMapping("/api/v1/admin/training/packages")
public class AdminTrainingPackageController {

    private final PackageService packageService;
    private final PackageVersionService versionService;
    private final PackageCourseService packageCourseService;
    private final PackageDetailService detailService;

    public AdminTrainingPackageController(PackageService packageService, PackageVersionService versionService,
                                          PackageCourseService packageCourseService,
                                          PackageDetailService detailService) {
        this.packageService = packageService;
        this.versionService = versionService;
        this.packageCourseService = packageCourseService;
        this.detailService = detailService;
    }

    @Operation(summary = "培训包分页")
    @GetMapping
    public ApiResponse<PageResult<PackageVo>> page(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long page,
                                                   @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(packageService.page(user, keyword, status, page, pageSize));
    }

    @Operation(summary = "新建培训包（自动创建 v1 草稿）")
    @PostMapping
    public ApiResponse<PackageVo> create(@Valid @RequestBody PackageRequest request, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(packageService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "培训包详情（含版本概要与指定版本的课程清单）")
    @GetMapping("/{id}")
    public ApiResponse<AdminPackageDetailVo> detail(@PathVariable long id,
                                                    @RequestParam(required = false) Integer versionNo) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(detailService.adminDetail(user, id, versionNo));
    }

    @Operation(summary = "修改培训包基本信息")
    @PutMapping("/{id}")
    public ApiResponse<PackageVo> update(@PathVariable long id, @Valid @RequestBody PackageRequest request,
                                         HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(packageService.update(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "培训包下线（不影响已发布版本与已下发任务）")
    @PostMapping("/{id}/offline")
    public ApiResponse<PackageVo> offline(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(packageService.offline(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "培训包恢复上线（要求存在已发布版本）")
    @PostMapping("/{id}/online")
    public ApiResponse<PackageVo> online(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(packageService.online(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "新建草稿版本")
    @PostMapping("/{id}/versions")
    public ApiResponse<PackageVersionVo> createVersion(@PathVariable long id,
                                                       @RequestBody(required = false) PackageVersionRequest request,
                                                       HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.create(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改草稿版本标题与简介")
    @PutMapping("/{id}/versions/{versionNo}")
    public ApiResponse<PackageVersionVo> updateVersion(@PathVariable long id, @PathVariable int versionNo,
                                                       @Valid @RequestBody PackageVersionRequest request,
                                                       HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.update(user, id, versionNo, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "发布版本（至少 1 门课程；发布后不可变）")
    @PostMapping("/{id}/versions/{versionNo}/publish")
    public ApiResponse<PackageVersionVo> publish(@PathVariable long id, @PathVariable int versionNo,
                                                 HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.publish(user, id, versionNo, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "复制版本为新草稿（深复制课程关联）")
    @PostMapping("/{id}/versions/{versionNo}/copy")
    public ApiResponse<PackageVersionVo> copy(@PathVariable long id, @PathVariable int versionNo,
                                              HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.copy(user, id, versionNo, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "关联已发布课程版本（重复关联幂等并按新 sortNo 重排）")
    @PostMapping("/{id}/versions/{versionNo}/courses")
    public ApiResponse<Void> bindCourse(@PathVariable long id, @PathVariable int versionNo,
                                        @Valid @RequestBody PackageCourseRequest request, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        packageCourseService.bind(user, id, versionNo, request, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok();
    }

    @Operation(summary = "解除课程关联（仅草稿版本）")
    @DeleteMapping("/{id}/versions/{versionNo}/courses/{courseVersionId}")
    public ApiResponse<Void> unbindCourse(@PathVariable long id, @PathVariable int versionNo,
                                          @PathVariable long courseVersionId, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        packageCourseService.unbind(user, id, versionNo, courseVersionId, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok();
    }
}