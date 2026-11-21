package com.insurance.training.identity.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.identity.dto.AdminPasswordResetRequest;
import com.insurance.training.identity.dto.AdminSelfPasswordRequest;
import com.insurance.training.identity.dto.AdminSelfProfileRequest;
import com.insurance.training.identity.dto.AdminUserCreateRequest;
import com.insurance.training.identity.dto.AdminUserUpdateRequest;
import com.insurance.training.identity.service.AdminUserService;
import com.insurance.training.identity.vo.AdminProfileResponse;
import com.insurance.training.identity.vo.AdminUserVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端账号管理接口。权限逐级自治，范围以后端判定为准。 */
@Tag(name = "管理端-账号管理")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService service;
    private final int maxPageSize;

    public AdminUserController(AdminUserService service,
                               @Value("${app.page.max-size:200}") int maxPageSize) {
        this.service = service;
        this.maxPageSize = maxPageSize;
    }

    @Operation(summary = "账号分页（按数据范围过滤）")
    @GetMapping
    public ApiResponse<PageResult<AdminUserVo>> page(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Long page,
                                                     @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(service.page(user, keyword, page, pageSize, maxPageSize));
    }

    @Operation(summary = "账号详情")
    @GetMapping("/{id}")
    public ApiResponse<AdminUserVo> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(DataScopeGuard.requireAdmin(), id));
    }

    @Operation(summary = "新建账号")
    @PostMapping
    public ApiResponse<AdminUserVo> create(@Valid @RequestBody AdminUserCreateRequest request,
                                           HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(service.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "编辑账号（含角色/归属/停用）")
    @PutMapping("/{id}")
    public ApiResponse<AdminUserVo> update(@PathVariable long id,
                                           @Valid @RequestBody AdminUserUpdateRequest request,
                                           HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(service.update(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "重置账号密码")
    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable long id,
                                           @Valid @RequestBody AdminPasswordResetRequest request,
                                           HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        service.resetPassword(user, id, request.password(), CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "删除账号（逻辑删除，用户名仍占用）")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        service.delete(user, id, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "自助改密（需旧密码）")
    @PutMapping("/me/password")
    public ApiResponse<AdminProfileResponse> changeSelfPassword(@Valid @RequestBody AdminSelfPasswordRequest request,
                                                                HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(service.changeSelfPassword(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "自助修改显示名")
    @PutMapping("/me")
    public ApiResponse<AdminProfileResponse> updateSelfProfile(@Valid @RequestBody AdminSelfProfileRequest request,
                                                               HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(service.updateSelfProfile(user, request, CurrentUserHolder.clientIp(http)));
    }
}
