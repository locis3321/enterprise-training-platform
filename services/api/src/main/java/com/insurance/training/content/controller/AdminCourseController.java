package com.insurance.training.content.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.content.dto.CourseRequest;
import com.insurance.training.content.dto.CourseVersionRequest;
import com.insurance.training.content.service.CourseService;
import com.insurance.training.content.service.CourseVersionService;
import com.insurance.training.content.vo.CourseVersionVo;
import com.insurance.training.content.vo.CourseVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程与版本管理接口。
 *
 * <p>注意：{@code GET /admin/courses/{id}} 的完整详情（含章节与资源）由 T5 提供，
 * 本控制器不重复定义该路由，避免 Spring 映射冲突。
 */
@Tag(name = "管理端-课程")
@RestController
@RequestMapping("/api/v1/admin/courses")
public class AdminCourseController {

    private final CourseService courseService;
    private final CourseVersionService versionService;

    public AdminCourseController(CourseService courseService, CourseVersionService versionService) {
        this.courseService = courseService;
        this.versionService = versionService;
    }

    @Operation(summary = "课程分页")
    @GetMapping
    public ApiResponse<PageResult<CourseVo>> page(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Long page,
                                                  @RequestParam(required = false) Long pageSize) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(courseService.page(user, keyword, status, page, pageSize));
    }

    @Operation(summary = "新建课程（自动创建 v1 草稿）")
    @PostMapping
    public ApiResponse<CourseVo> create(@Valid @RequestBody CourseRequest request, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(courseService.create(user, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改课程基本信息")
    @PutMapping("/{id}")
    public ApiResponse<CourseVo> update(@PathVariable long id, @Valid @RequestBody CourseRequest request,
                                        HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(courseService.update(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "删除课程（存在已发布版本时拒绝）")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        courseService.delete(user, id, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok();
    }

    @Operation(summary = "课程下线")
    @PostMapping("/{id}/offline")
    public ApiResponse<CourseVo> offline(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(courseService.offline(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "课程恢复上线")
    @PostMapping("/{id}/online")
    public ApiResponse<CourseVo> online(@PathVariable long id, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(courseService.online(user, id, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "版本列表")
    @GetMapping("/{id}/versions")
    public ApiResponse<List<CourseVersionVo>> versions(@PathVariable long id) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.list(user, id));
    }

    @Operation(summary = "新建草稿版本")
    @PostMapping("/{id}/versions")
    public ApiResponse<CourseVersionVo> createVersion(@PathVariable long id,
                                                      @RequestBody(required = false) CourseVersionRequest request,
                                                      HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.create(user, id, request, CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改草稿版本")
    @PutMapping("/{id}/versions/{versionNo}")
    public ApiResponse<CourseVersionVo> updateVersion(@PathVariable long id, @PathVariable int versionNo,
                                                      @Valid @RequestBody CourseVersionRequest request,
                                                      HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.update(user, id, versionNo, request,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "发布版本")
    @PostMapping("/{id}/versions/{versionNo}/publish")
    public ApiResponse<CourseVersionVo> publish(@PathVariable long id, @PathVariable int versionNo,
                                                HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.publish(user, id, versionNo,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "复制版本为新草稿")
    @PostMapping("/{id}/versions/{versionNo}/copy")
    public ApiResponse<CourseVersionVo> copy(@PathVariable long id, @PathVariable int versionNo,
                                             HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(versionService.copy(user, id, versionNo, CurrentUserHolder.clientIp(http)));
    }
}
