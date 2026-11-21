package com.insurance.training.exam.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.exam.dto.QuestionRequest;
import com.insurance.training.exam.imports.QuestionImportResult;
import com.insurance.training.exam.imports.QuestionImportService;
import com.insurance.training.exam.service.QuestionService;
import com.insurance.training.exam.vo.QuestionVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 管理端题库：按课程维护单选/多选题。 */
@Tag(name = "管理端-题库")
@RestController
@RequestMapping("/api/v1/admin/courses/{courseId}/questions")
public class AdminQuestionController {

    private final QuestionService questionService;
    private final QuestionImportService importService;

    public AdminQuestionController(QuestionService questionService, QuestionImportService importService) {
        this.questionService = questionService;
        this.importService = importService;
    }

    @Operation(summary = "题目分页")
    @GetMapping
    public ApiResponse<PageResult<QuestionVo>> page(
            @PathVariable long courseId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(questionService.page(user, courseId, type, status, page, pageSize));
    }

    @Operation(summary = "题目详情")
    @GetMapping("/{id}")
    public ApiResponse<QuestionVo> get(@PathVariable long courseId, @PathVariable long id) {
        DataScopeGuard.requireAdmin();
        return ApiResponse.ok(questionService.detail(id));
    }

    @Operation(summary = "新建题目")
    @PostMapping
    public ApiResponse<QuestionVo> create(@PathVariable long courseId,
                                          @Valid @RequestBody QuestionRequest request,
                                          HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(questionService.create(user, courseId, request,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改题目（被已发布试卷引用时拒绝）")
    @PutMapping("/{id}")
    public ApiResponse<QuestionVo> update(@PathVariable long courseId, @PathVariable long id,
                                          @Valid @RequestBody QuestionRequest request,
                                          HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(questionService.update(user, id, request,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "删除题目（被已发布试卷引用时拒绝）")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long courseId, @PathVariable long id,
                                    HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        questionService.delete(user, id, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "停用 / 启用（被已发布试卷引用时仍允许，不影响既有判分）")
    @PutMapping("/{id}/status")
    public ApiResponse<QuestionVo> changeStatus(@PathVariable long courseId, @PathVariable long id,
                                                @RequestBody Map<String, String> body,
                                                HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(questionService.changeStatus(user, id, body.get("status"),
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "导入模板下载")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@PathVariable long courseId) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        questionService.requireVisibleCourse(user, courseId);
        byte[] body = importService.template();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("question-import-template.xlsx", StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @Operation(summary = "Excel 导入题目（逐行校验，失败行不影响其他行）")
    @PostMapping("/import")
    public ApiResponse<QuestionImportResult> importQuestions(@PathVariable long courseId,
                                                             @RequestParam("file") MultipartFile file,
                                                             HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(importService.importFile(user, courseId, file,
                CurrentUserHolder.clientIp(http)));
    }
}
