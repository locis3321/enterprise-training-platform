package com.insurance.training.content.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.content.entity.CourseResource;
import com.insurance.training.content.service.MiniappResourceAccessChecker;
import com.insurance.training.content.storage.MinioStorageService;
import com.insurance.training.content.storage.ResourceKind;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学员获取资源下载地址。 */
@Tag(name = "小程序-课程资源")
@RestController
@RequestMapping("/api/v1/miniapp")
public class MiniappResourceController {

    private final MiniappResourceAccessChecker accessChecker;
    private final MinioStorageService storage;

    public MiniappResourceController(MiniappResourceAccessChecker accessChecker, MinioStorageService storage) {
        this.accessChecker = accessChecker;
        this.storage = storage;
    }

    @Operation(summary = "获取资源签名地址（学员）")
    @GetMapping("/courses/{courseId}/versions/latest/resources/{resourceId}/url")
    public ApiResponse<String> url(@PathVariable long courseId, @PathVariable long resourceId) {
        CurrentUser user = CurrentUserHolder.require();
        CourseResource resource = accessChecker.requireAccess(user, courseId, resourceId);
        // 视频 inline 便于播放；文档按附件下载，避免浏览器尝试内联渲染
        String downloadName = ResourceKind.valueOf(resource.getKind()) == ResourceKind.VIDEO
                ? null : resource.getOriginalName();
        return ApiResponse.ok(storage.presignedGetUrl(resource.getObjectKey(),
                ResourceKind.valueOf(resource.getKind()), downloadName));
    }
}
