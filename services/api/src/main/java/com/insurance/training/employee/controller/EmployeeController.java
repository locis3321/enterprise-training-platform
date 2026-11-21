package com.insurance.training.employee.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.employee.dto.EmployeeRequest;
import com.insurance.training.employee.service.EmployeeService;
import com.insurance.training.employee.vo.EmployeeVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 员工档案管理接口。 */
@Tag(name = "管理端-员工")
@RestController
@RequestMapping("/api/v1/admin/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @Operation(summary = "员工分页")
    @GetMapping
    public ApiResponse<PageResult<EmployeeVo>> page(
            @RequestParam(required = false) Long enterpriseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(employeeService.page(user, enterpriseId, keyword, status, page, pageSize));
    }

    @Operation(summary = "员工详情")
    @GetMapping("/{id}")
    public ApiResponse<EmployeeVo> get(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(EmployeeVo.of(employeeService.getVisible(user, id)));
    }

    @Operation(summary = "新建员工")
    @PostMapping
    public ApiResponse<EmployeeVo> create(@Valid @RequestBody EmployeeRequest request, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(employeeService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改员工")
    @PutMapping("/{id}")
    public ApiResponse<EmployeeVo> update(@PathVariable long id, @Valid @RequestBody EmployeeRequest request,
                                          HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(employeeService.update(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    /**
     * 重置学员密码为初始值（anzhi + 手机号后 6 位）。
     *
     * <p>响应含 {@code affectedEnterpriseCount}：该手机号关联的启用企业数。
     * 账号按手机号全局唯一，重置会影响该学员在所有企业的登录，界面必须提示。
     */
    @Operation(summary = "重置学员密码为初始密码（返回受影响企业数）")
    @PostMapping("/{id}/password/reset")
    public ApiResponse<EmployeeService.PasswordResetResult> resetPassword(@PathVariable long id,
                                                                          HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(employeeService.resetPassword(user, id, CurrentUserHolder.clientIp(http)));
    }
}
