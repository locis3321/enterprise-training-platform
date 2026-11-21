package com.insurance.training.report.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.report.service.ReportService;
import com.insurance.training.report.vo.EnterpriseSummaryVo;
import com.insurance.training.report.vo.LearnerReportVo;
import com.insurance.training.report.vo.TaskSummaryVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 培训报表查询（只读，范围由后端强制裁剪）。 */
@Tag(name = "管理端-培训报表")
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    private final ReportService reportService;

    public AdminReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "子公司维度汇总")
    @GetMapping("/enterprise-summary")
    public ApiResponse<PageResult<EnterpriseSummaryVo>> enterpriseSummary(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(reportService.enterpriseSummary(user, keyword, page, pageSize));
    }

    @Operation(summary = "任务维度汇总")
    @GetMapping("/task-summary")
    public ApiResponse<PageResult<TaskSummaryVo>> taskSummary(
            @RequestParam(required = false) Long enterpriseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(reportService.taskSummary(user, enterpriseId, keyword, page, pageSize));
    }

    @Operation(summary = "个人学习与考试明细")
    @GetMapping("/learners/{employeeId}")
    public ApiResponse<LearnerReportVo> learner(@PathVariable long employeeId) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(reportService.learner(user, employeeId));
    }
}
