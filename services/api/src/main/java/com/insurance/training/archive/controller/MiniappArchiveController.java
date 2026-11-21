package com.insurance.training.archive.controller;

import com.insurance.training.archive.service.ArchiveService;
import com.insurance.training.archive.vo.ExamRecordVo;
import com.insurance.training.archive.vo.LearningArchiveVo;
import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学员培训档案（只读）。
 *
 * <p>「培训档案」与「考试记录」是同一页面的两个 tab：前者累计汇总与任务归档，
 * 后者全部考试流水。两者都不重复「考试」页的待考列表与「成绩」页的单任务最佳。
 */
@Tag(name = "小程序-培训档案")
@RestController
@RequestMapping("/api/v1/miniapp/archive")
public class MiniappArchiveController {

    private final ArchiveService archiveService;

    public MiniappArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Operation(summary = "学习档案：累计汇总 + 任务归档")
    @GetMapping("/learning")
    public ApiResponse<LearningArchiveVo> learning() {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(archiveService.learning(user));
    }

    @Operation(summary = "考试记录流水（跨任务，按交卷时间倒序）")
    @GetMapping("/exams")
    public ApiResponse<PageResult<ExamRecordVo>> exams(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long pageSize) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(archiveService.exams(user, page, pageSize));
    }
}
