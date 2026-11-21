package com.insurance.training.content.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.common.security.DataScopeGuard;
import com.insurance.training.content.service.CourseService;
import com.insurance.training.content.service.MiniappResourceAccessChecker;
import com.insurance.training.content.service.ResourceBindingService;
import com.insurance.training.content.service.ResourceUploadService;
import com.insurance.training.content.storage.MinioStorageService;
import com.insurance.training.content.storage.ResourceKind;
import com.insurance.training.content.vo.ResourceVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资源上传、绑定与下载地址。
 *
 * <p>上传只落元数据；绑定用绑定级路径（同一资源可被多版本多章节引用）。
 */
@Tag(name = "管理端-课程资源")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminResourceController {

    private final ResourceUploadService uploadService;
    private final ResourceBindingService bindingService;
    private final MiniappResourceAccessChecker accessChecker;
    private final CourseService courseService;
    private final MinioStorageService storage;

    public AdminResourceController(ResourceUploadService uploadService, ResourceBindingService bindingService,
                                   MiniappResourceAccessChecker accessChecker, CourseService courseService,
                                   MinioStorageService storage) {
        this.uploadService = uploadService;
        this.bindingService = bindingService;
        this.accessChecker = accessChecker;
        this.courseService = courseService;
        this.storage = storage;
    }

    @Operation(summary = "上传课程资源（仅落元数据，不绑定）")
    @PostMapping("/resources/upload")
    public ApiResponse<ResourceVo> upload(@RequestParam("courseId") long courseId,
                                          @RequestParam(value = "kind", required = false) ResourceKind kind,
                                          @RequestParam("file") MultipartFile file,
                                          HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        return ApiResponse.ok(uploadService.upload(user, courseId, kind, file,
                CurrentUserHolder.clientIp(http)));
    }

    @Operation(summary = "绑定资源到章节（仅 DRAFT 版本）")
    @PutMapping("/courses/{courseId}/versions/{versionNo}/sections/{sectionId}/resources/{resourceId}")
    public ApiResponse<Void> bind(@PathVariable long courseId, @PathVariable int versionNo,
                                  @PathVariable long sectionId, @PathVariable long resourceId,
                                  HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        bindingService.bind(user, courseId, versionNo, sectionId, resourceId,
                CurrentUserHolder.clientIp(http));
        return ApiResponse.ok();
    }

    @Operation(summary = "解绑资源（仅 DRAFT 版本）")
    @DeleteMapping("/courses/{courseId}/versions/{versionNo}/sections/{sectionId}/resources/{resourceId}")
    public ApiResponse<Void> unbind(@PathVariable long courseId, @PathVariable int versionNo,
                                    @PathVariable long sectionId, @PathVariable long resourceId,
                                    HttpServletRequest http) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        bindingService.unbind(user, courseId, versionNo, sectionId, resourceId,
                CurrentUserHolder.clientIp(http));
        return ApiResponse.ok();
    }

    @Operation(summary = "管理端获取资源下载地址")
    @RequestMapping("/resources/{resourceId}/url")
    public ApiResponse<String> adminUrl(@PathVariable long resourceId) {
        CurrentUser user = DataScopeGuard.requireAdmin();
        var resource = accessChecker.requireAdminResource(user, resourceId);
        return ApiResponse.ok(storage.presignedGetUrl(resource.getObjectKey(),
                ResourceKind.valueOf(resource.getKind()), resource.getOriginalName()));
    }
}
