package com.insurance.training.learning.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.learning.dto.HeartbeatRequest;
import com.insurance.training.learning.dto.HeartbeatResponse;
import com.insurance.training.learning.dto.OpenResourceRequest;
import com.insurance.training.learning.dto.OpenResourceResponse;
import com.insurance.training.learning.service.LearningProgressQueryService;
import com.insurance.training.learning.service.LearningService;
import com.insurance.training.learning.vo.MiniappOverviewVo;
import com.insurance.training.learning.vo.MiniappProgressVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学员学习：视频心跳与非视频资源打开。两条路径互斥。 */
@Tag(name = "小程序-学习")
@RestController
@RequestMapping("/api/v1/miniapp/learning")
public class MiniappLearningController {

    private final LearningService learningService;
    private final LearningProgressQueryService progressQueryService;

    public MiniappLearningController(LearningService learningService,
                                     LearningProgressQueryService progressQueryService) {
        this.learningService = learningService;
        this.progressQueryService = progressQueryService;
    }

    @Operation(summary = "我的学习总览（首页统计，考试未实现时 pendingExams=0）")
    @GetMapping("/progress")
    public ApiResponse<MiniappOverviewVo> overview() {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(progressQueryService.overview(user));
    }

    @Operation(summary = "单个任务的学习进度（含章节完成判定）")
    @GetMapping("/progress/{assignmentId}")
    public ApiResponse<MiniappProgressVo> progress(@PathVariable long assignmentId) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(progressQueryService.byAssignment(user, assignmentId));
    }

    @Operation(summary = "视频心跳（服务端计时，客户端字段不参与计算）")
    @PostMapping("/heartbeat")
    public ApiResponse<HeartbeatResponse> heartbeat(@Valid @RequestBody HeartbeatRequest request,
                                                    HttpServletRequest http) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(learningService.heartbeat(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "非视频资源已打开登记（幂等，不推进视频时钟）")
    @PostMapping("/resources/open")
    public ApiResponse<OpenResourceResponse> open(@Valid @RequestBody OpenResourceRequest request,
                                                  HttpServletRequest http) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(learningService.open(user, request, CurrentUserHolder.clientIp(http)));
    }
}