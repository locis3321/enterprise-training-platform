package com.insurance.training.exam.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.exam.dto.SaveAnswerRequest;
import com.insurance.training.exam.service.ExamAttemptService;
import com.insurance.training.exam.vo.AttemptVo;
import com.insurance.training.identity.learner.LearnerContext;
import com.insurance.training.identity.learner.LearnerGuard;
import com.insurance.training.training.entity.TrainingAssignment;
import com.insurance.training.training.mapper.TrainingAssignmentMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 小程序考试：可考列表、开考、答题、交卷、历史。 */
@Tag(name = "小程序-考试")
@RestController
@RequestMapping("/api/v1/miniapp/exams")
public class MiniappExamController {

    private final ExamAttemptService attemptService;
    private final LearnerGuard learnerGuard;
    private final TrainingAssignmentMapper assignmentMapper;

    public MiniappExamController(ExamAttemptService attemptService, LearnerGuard learnerGuard,
                                 TrainingAssignmentMapper assignmentMapper) {
        this.attemptService = attemptService;
        this.learnerGuard = learnerGuard;
        this.assignmentMapper = assignmentMapper;
    }

    /**
     * 可考状态。
     *
     * <p>{@code canStart} 与 {@code reason} 都由服务端给出，前端不推算——
     * 「学习是否完成」「任务是否在窗口内」「包是否下线」都在服务端判定。
     */
    public record ExamAvailability(boolean canStart, String reason, List<Long> paperIds) {
    }

    @Operation(summary = "考试总览（首页「待考考试」真实值）")
    @GetMapping("/overview")
    public ApiResponse<ExamAttemptService.ExamOverview> overview() {
        CurrentUser user = CurrentUserHolder.require();
        LearnerContext learner = learnerGuard.requireLearner(user);
        return ApiResponse.ok(attemptService.overview(learner));
    }

    @Operation(summary = "考试卡片列表（只含有已发布试卷的任务，含最高分）")
    @GetMapping("/assignments")
    public ApiResponse<List<ExamAttemptService.ExamCard>> assignments() {
        CurrentUser user = CurrentUserHolder.require();
        LearnerContext learner = learnerGuard.requireLearner(user);
        return ApiResponse.ok(attemptService.examCards(learner));
    }

    @Operation(summary = "考试可用性（是否已完成学习、有哪些可考试卷）")
    @GetMapping("/available/{assignmentId}")
    public ApiResponse<ExamAvailability> available(@PathVariable long assignmentId) {
        CurrentUser user = CurrentUserHolder.require();
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            return ApiResponse.ok(new ExamAvailability(false, "任务分配不存在", List.of()));
        }
        if (!learner.owns(assignment.getEmployeeId())) {
            return ApiResponse.ok(new ExamAvailability(false, "无权访问他人的培训任务", List.of()));
        }
        ExamAttemptService.Availability result = attemptService.availability(assignment);
        return ApiResponse.ok(new ExamAvailability(result.canStart(), result.reason(), result.paperIds()));
    }

    @Operation(summary = "某任务的最佳已交卷成绩（未考过返回 data=null）")
    @GetMapping("/best/{assignmentId}")
    public ApiResponse<ExamAttemptService.BestAttempt> best(@PathVariable long assignmentId) {
        CurrentUser user = CurrentUserHolder.require();
        LearnerContext learner = learnerGuard.requireLearner(user);
        TrainingAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null || !learner.owns(assignment.getEmployeeId())) {
            throw new com.insurance.training.common.exception.BizException(
                    com.insurance.training.common.response.ErrorCode.FORBIDDEN, "无权访问他人的培训任务");
        }
        return ApiResponse.ok(attemptService.bestOf(assignmentId));
    }

    @Operation(summary = "开考（幂等：已有未超时的进行中考试则恢复同一份）")
    @PostMapping("/{paperId}/attempts")
    public ApiResponse<AttemptVo> start(@PathVariable long paperId,
                                        @RequestParam long assignmentId,
                                        HttpServletRequest http) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(attemptService.start(user, paperId, assignmentId,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "保存单题答案（幂等覆盖）")
    @PostMapping("/answers")
    public ApiResponse<Void> saveAnswer(@Valid @RequestBody SaveAnswerRequest request,
                                        HttpServletRequest http) {
        CurrentUser user = CurrentUserHolder.require();
        attemptService.saveAnswer(user, request, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(null);
    }

    @Operation(summary = "交卷并判分（幂等；auto=true 表示切页自动交卷）")
    @PostMapping("/attempts/{attemptId}/submit")
    public ApiResponse<AttemptVo> submit(@PathVariable long attemptId,
                                         @RequestParam(defaultValue = "false") boolean auto,
                                         HttpServletRequest http) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(attemptService.submit(user, attemptId, auto,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "我的考试历史（同一试卷多次考试全部保留）")
    @GetMapping("/attempts")
    public ApiResponse<List<AttemptVo>> history(@RequestParam long assignmentId) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(attemptService.history(user, assignmentId));
    }
}
