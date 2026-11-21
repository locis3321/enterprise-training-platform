package com.insurance.training.exam.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.exam.dto.PaperCreateRequest;
import com.insurance.training.exam.entity.ExamPaper;
import com.insurance.training.exam.entity.ExamPaperQuestion;
import com.insurance.training.exam.service.ExamPaperService;
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

import java.util.List;
import java.util.Map;

/** 管理端试卷：组卷、改分、发布。 */
@Tag(name = "管理端-试卷")
@RestController
@RequestMapping("/api/v1/admin/exam-papers")
public class AdminExamPaperController {

    private final ExamPaperService paperService;

    public AdminExamPaperController(ExamPaperService paperService) {
        this.paperService = paperService;
    }

    @Operation(summary = "组卷（按课程抽题，生成草稿）")
    @PostMapping
    public ApiResponse<ExamPaper> create(@Valid @RequestBody PaperCreateRequest request,
                                         HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(paperService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "试卷详情（含题目与分值）")
    @GetMapping("/{id}")
    public ApiResponse<PaperDetail> get(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        ExamPaper paper = paperService.requireVisible(user, id);
        List<ExamPaperQuestion> questions = paperService.questionsOf(id);
        return ApiResponse.ok(new PaperDetail(paper, questions, paperService.totalScore(id)));
    }

    @Operation(summary = "按培训包查试卷（一个包最多一套，无试卷返回 data=null）")
    @GetMapping("/by-package/{packageId}")
    public ApiResponse<PaperDetail> byPackage(@PathVariable long packageId) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        ExamPaper paper = paperService.findByPackage(user, packageId);
        if (paper == null) {
            return ApiResponse.ok(null);
        }
        return ApiResponse.ok(new PaperDetail(paper, paperService.questionsOf(paper.getId()),
                paperService.totalScore(paper.getId())));
    }

    @Operation(summary = "逐题设置分值（草稿）")
    @PutMapping("/{id}/scores")
    public ApiResponse<Void> updateScores(@PathVariable long id,
                                          @RequestBody List<ExamPaperService.ScoreItem> items,
                                          HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        paperService.updateScores(user, id, items, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "修改时长与及格百分比（草稿）")
    @PutMapping("/{id}/settings")
    public ApiResponse<ExamPaper> updateSettings(@PathVariable long id,
                                                 @RequestBody Map<String, Integer> body,
                                                 HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(paperService.updateSettings(user, id, body.get("durationMinutes"),
                body.get("passScore"), CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "发布（发布后锁定，不可再改题目与分值）")
    @PostMapping("/{id}/publish")
    public ApiResponse<ExamPaper> publish(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(paperService.publish(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "取消发布（恢复可编辑；进行中的考试仍可考完）")
    @PostMapping("/{id}/unpublish")
    public ApiResponse<ExamPaper> unpublish(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(paperService.unpublish(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "试卷题目分页（带题干、题型与选项数）")
    @GetMapping("/{id}/questions")
    public ApiResponse<PageResult<ExamPaperService.PaperQuestionItem>> questionPage(
            @PathVariable long id,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(paperService.questionPage(user, id, page, pageSize));
    }

    @Operation(summary = "复制到另一个培训包（新试卷为草稿，与源试卷独立）")
    @PostMapping("/{id}/copy")
    public ApiResponse<ExamPaper> copy(@PathVariable long id,
                                       @Valid @RequestBody PaperCopyRequest request,
                                       HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(paperService.copy(user, id, request.targetPackageId(), request.title(),
                CurrentUserHolder.clientIp(http)));
    }

    /** 试卷详情视图。 */
    public record PaperDetail(ExamPaper paper, List<ExamPaperQuestion> questions, int totalScore) {
    }

    /** 复制试卷请求：复制必须落到另一个培训包，因为一个包只允许一套试卷。 */
    public record PaperCopyRequest(
            @jakarta.validation.constraints.NotNull(message = "目标培训包不能为空")
            Long targetPackageId,
            @jakarta.validation.constraints.Size(max = 200, message = "试卷名称不能超过 200")
            String title
    ) {
    }
}
