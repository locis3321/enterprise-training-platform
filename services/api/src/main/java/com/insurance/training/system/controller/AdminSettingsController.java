package com.insurance.training.system.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.system.service.SystemConfigService;
import com.insurance.training.system.vo.SystemSettingVo;
import com.insurance.training.system.vo.SystemSettingsViewVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统设置（仅平台管理员）。 */
@Tag(name = "管理端-系统设置")
@RestController
@RequestMapping("/api/v1/admin/settings")
public class AdminSettingsController {

    public record SettingUpdateRequest(@NotNull(message = "配置值不能为 null") String value) {
    }

    private final SystemConfigService service;

    public AdminSettingsController(SystemConfigService service) {
        this.service = service;
    }

    @Operation(summary = "列出全部配置项与刷新状态")
    @GetMapping
    public ApiResponse<SystemSettingsViewVo> list() {
        DataScopeGuard.requireSuperAdmin();
        return ApiResponse.ok(new SystemSettingsViewVo(service.listSettings(),
                service.lastRefreshAt(), service.lastRefreshError(),
                "保存后本节点立即生效，其他节点最长 30 秒"));
    }

    @Operation(summary = "修改配置项")
    @PutMapping("/{key}")
    public ApiResponse<SystemSettingVo> update(@PathVariable String key,
                                               @Valid @RequestBody SettingUpdateRequest request,
                                               HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireSuperAdmin();
        return ApiResponse.ok(service.update(user, key, request.value(), CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "恢复出厂默认")
    @DeleteMapping("/{key}")
    public ApiResponse<Void> reset(@PathVariable String key, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireSuperAdmin();
        service.reset(user, key, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "手动全量刷新（多节点立即同步用）")
    @PostMapping("/refresh")
    public ApiResponse<SystemSettingsViewVo> refresh() {
        DataScopeGuard.requireSuperAdmin();
        service.refresh();
        return ApiResponse.ok(new SystemSettingsViewVo(service.listSettings(),
                service.lastRefreshAt(), service.lastRefreshError(),
                "保存后本节点立即生效，其他节点最长 30 秒"));
    }
}
