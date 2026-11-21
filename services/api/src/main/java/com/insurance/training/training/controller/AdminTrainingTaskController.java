package com.insurance.training.training.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.training.dto.AssignRequest;
import com.insurance.training.training.dto.TaskRequest;
import com.insurance.training.training.service.TaskService;
import com.insurance.training.training.vo.TaskAssignmentVo;
import com.insurance.training.training.vo.TaskDetailVo;
import com.insurance.training.training.vo.TaskProgressVo;
import com.insurance.training.training.vo.TaskVo;
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
import java.util.Map;

/** 培训任务与分配。培训包的编排见 /admin/training/packages。 */
@Tag(name = "管理端-培训任务")
@RestController
@RequestMapping("/api/v1/admin/training/tasks")
public class AdminTrainingTaskController {

    private final TaskService taskService;

    public AdminTrainingTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @Operation(summary = "新建培训任务（创建即发布，并完成分配）")
    @PostMapping
    public ApiResponse<TaskVo> create(@Valid @RequestBody TaskRequest request, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "培训任务分页")
    @GetMapping
    public ApiResponse<PageResult<TaskVo>> page(@RequestParam(required = false) Long enterpriseId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Long page,
                                                @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.page(user, enterpriseId, status, keyword, page, pageSize));
    }

    @Operation(summary = "培训任务详情（含进度统计）")
    @GetMapping("/{id}")
    public ApiResponse<TaskDetailVo> detail(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.detail(user, id));
    }

    @Operation(summary = "追加分配员工（任务关闭后拒绝）")
    @PostMapping("/{id}/assign")
    public ApiResponse<Map<String, Integer>> assign(@PathVariable long id,
                                                    @Valid @RequestBody AssignRequest request,
                                                    HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        int inserted = taskService.assignMore(user, id, request.employeeIds(), CurrentUserHolder.clientIp(http));
        return ApiResponse.ok(Map.of("inserted", inserted));
    }

    @Operation(summary = "给原任务追加新版本任务（分配给原任务同一批学员）")
    @PostMapping("/{id}/successor")
    public ApiResponse<TaskVo> createSuccessor(@PathVariable long id,
                                               @Valid @RequestBody TaskRequest request,
                                               HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.createSuccessor(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "关闭任务（学员不可继续）")
    @PostMapping("/{id}/close")
    public ApiResponse<TaskVo> close(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.close(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "逐人分配明细（含各人累计有效时长）")
    @GetMapping("/{id}/assignments")
    public ApiResponse<List<TaskAssignmentVo>> assignors(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.assignmentRows(user, id));
    }

    @Operation(summary = "任务进度统计")
    @GetMapping("/{id}/progress")
    public ApiResponse<TaskProgressVo> progress(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(taskService.progress(user, id));
    }
}