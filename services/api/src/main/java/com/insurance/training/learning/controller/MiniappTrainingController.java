package com.insurance.training.learning.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.learning.service.MiniappTrainingQueryService;
import com.insurance.training.learning.vo.MiniappAssignmentVo;
import com.insurance.training.learning.vo.MiniappTaskDetailVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学员侧培训任务。 */
@Tag(name = "小程序-培训任务")
@RestController
@RequestMapping("/api/v1/miniapp/training")
public class MiniappTrainingController {

    private final MiniappTrainingQueryService queryService;

    public MiniappTrainingController(MiniappTrainingQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "我的培训任务")
    @GetMapping("/tasks")
    public ApiResponse<PageResult<MiniappAssignmentVo>> myTasks(@RequestParam(required = false) Long page,
                                                                @RequestParam(required = false) Long pageSize) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(queryService.myTasks(user, page, pageSize));
    }

    @Operation(summary = "我的任务详情（含课程、章节与我的进度）")
    @GetMapping("/tasks/{assignmentId}")
    public ApiResponse<MiniappTaskDetailVo> detail(@PathVariable long assignmentId) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(queryService.detail(user, assignmentId));
    }

    @Operation(summary = "任务上下文取资源签名地址（版本由 assignment 推导）")
    @GetMapping("/assignments/{assignmentId}/courses/{courseId}/resources/{resourceId}/url")
    public ApiResponse<String> resourceUrl(@PathVariable long assignmentId, @PathVariable long courseId,
                                           @PathVariable long resourceId) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(queryService.resourceUrl(user, assignmentId, courseId, resourceId));
    }
}