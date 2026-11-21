package com.insurance.training.content.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.content.dto.SectionRequest;
import com.insurance.training.content.entity.CourseSection;
import com.insurance.training.content.service.CourseDetailService;
import com.insurance.training.content.service.CourseSectionService;
import com.insurance.training.content.vo.AdminCourseDetailVo;
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

/** 管理端章节编排与课程完整详情（该详情路由由本控制器独占）。 */
@Tag(name = "管理端-章节与详情")
@RestController
@RequestMapping("/api/v1/admin/courses")
public class AdminCourseDetailController {

    private final CourseSectionService sectionService;
    private final CourseDetailService detailService;

    public AdminCourseDetailController(CourseSectionService sectionService,
                                       CourseDetailService detailService) {
        this.sectionService = sectionService;
        this.detailService = detailService;
    }

    @Operation(summary = "课程完整详情（可指定版本）")
    @GetMapping("/{courseId}")
    public ApiResponse<AdminCourseDetailVo> detail(@PathVariable long courseId,
                                                   @RequestParam(required = false) Integer versionNo) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(detailService.adminDetail(user, courseId, versionNo));
    }

    @Operation(summary = "新增章节（仅草稿版本）")
    @PostMapping("/{courseId}/versions/{versionNo}/sections")
    public ApiResponse<CourseSection> createSection(@PathVariable long courseId, @PathVariable int versionNo,
                                                    @Valid @RequestBody SectionRequest request,
                                                    HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(sectionService.create(user, courseId, versionNo, request,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "修改章节（仅草稿版本）")
    @PutMapping("/{courseId}/versions/{versionNo}/sections/{sectionId}")
    public ApiResponse<CourseSection> updateSection(@PathVariable long courseId, @PathVariable int versionNo,
                                                    @PathVariable long sectionId,
                                                    @Valid @RequestBody SectionRequest request,
                                                    HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(sectionService.update(user, courseId, versionNo, sectionId, request,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "删除章节（有资源绑定时拒绝）")
    @DeleteMapping("/{courseId}/versions/{versionNo}/sections/{sectionId}")
    public ApiResponse<Void> deleteSection(@PathVariable long courseId, @PathVariable int versionNo,
                                           @PathVariable long sectionId, HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        sectionService.delete(user, courseId, versionNo, sectionId, CurrentUserHolder.clientIp(http));
        return ApiResponse.ok();
    }
}
