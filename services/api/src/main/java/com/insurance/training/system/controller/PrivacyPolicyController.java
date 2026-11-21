package com.insurance.training.system.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.system.service.PrivacyPolicyService;
import com.insurance.training.system.vo.PrivacyPolicyVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 隐私协议：学员端公开读取 + 管理端上传替换。
 *
 * <p>读取路径放在 {@code /miniapp/legal/} 下并加入鉴权白名单——登录前也要能查看协议。
 */
@Tag(name = "隐私协议")
@RestController
public class PrivacyPolicyController {

    private final PrivacyPolicyService service;

    public PrivacyPolicyController(PrivacyPolicyService service) {
        this.service = service;
    }

    @Operation(summary = "获取隐私协议（免登录，返回实时签名地址）")
    @GetMapping("/api/v1/miniapp/legal/privacy-policy")
    public ApiResponse<PrivacyPolicyVo> current() {
        return ApiResponse.ok(service.current());
    }

    @Operation(summary = "管理端查看当前隐私协议")
    @GetMapping("/api/v1/admin/settings/legal/privacy-policy")
    public ApiResponse<PrivacyPolicyVo> adminCurrent() {
        DataScopeGuard.requireSuperAdmin();
        return ApiResponse.ok(service.current());
    }

    @Operation(summary = "上传替换隐私协议 PDF（仅平台管理员）")
    @PutMapping("/api/v1/admin/settings/legal/privacy-policy")
    public ApiResponse<PrivacyPolicyVo> upload(@RequestParam("file") MultipartFile file,
                                               HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireSuperAdmin();
        return ApiResponse.ok(service.upload(user, file, CurrentUserHolder.clientIp(http)));
    }
}
