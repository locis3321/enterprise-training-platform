package com.insurance.training.report.controller;

import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.report.service.ReportExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 报表 Excel 导出。响应是 xlsx 字节流，不走统一 JSON 包装（与既有模板下载一致）。 */
@Tag(name = "管理端-报表导出")
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportExportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportExportService exportService;

    public ReportExportController(ReportExportService exportService) {
        this.exportService = exportService;
    }

    @Operation(summary = "导出子公司汇总")
    @GetMapping("/enterprise-summary/export")
    public ResponseEntity<ByteArrayResource> enterpriseSummaryExport(
            @RequestParam(required = false) String keyword, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        var file = exportService.enterpriseSummary(user, keyword, CurrentUserHolder.clientIp(http));
        return download(file);
    }

    @Operation(summary = "导出任务汇总")
    @GetMapping("/task-summary/export")
    public ResponseEntity<ByteArrayResource> taskSummaryExport(
            @RequestParam(required = false) Long enterpriseId,
            @RequestParam(required = false) String keyword,
            HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        var file = exportService.taskSummary(user, enterpriseId, keyword, CurrentUserHolder.clientIp(http));
        return download(file);
    }

    @Operation(summary = "导出任务逐人名单")
    @GetMapping("/tasks/{taskId}/learners/export")
    public ResponseEntity<ByteArrayResource> taskLearnersExport(@PathVariable long taskId,
                                                                HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        var file = exportService.taskLearners(user, taskId, CurrentUserHolder.clientIp(http));
        return download(file);
    }

    @Operation(summary = "导出个人明细")
    @GetMapping("/learners/{employeeId}/export")
    public ResponseEntity<ByteArrayResource> learnerExport(@PathVariable long employeeId,
                                                           HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        var file = exportService.learner(user, employeeId, CurrentUserHolder.clientIp(http));
        return download(file);
    }

    private ResponseEntity<ByteArrayResource> download(ReportExportService.ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(XLSX)
                .contentLength(file.body().length)
                .body(new ByteArrayResource(file.body()));
    }
}
