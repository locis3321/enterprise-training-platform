package com.insurance.training.content.controller;

import com.insurance.training.common.response.ApiResponse;
import com.insurance.training.common.response.PageResult;
import com.insurance.training.common.security.CurrentUser;
import com.insurance.training.common.security.CurrentUserHolder;
import com.insurance.training.content.service.CourseDetailService;
import com.insurance.training.content.vo.CourseVo;
import com.insurance.training.content.vo.MiniappCourseDetailVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学员课程浏览：只返回已发布版本。 */
@Tag(name = "小程序-课程")
@RestController
@RequestMapping("/api/v1/miniapp/courses")
public class MiniappCourseController {

    private final CourseDetailService detailService;

    public MiniappCourseController(CourseDetailService detailService) {
        this.detailService = detailService;
    }

    @Operation(summary = "我的课程列表（本集团已发布）")
    @GetMapping
    public ApiResponse<PageResult<CourseVo>> list(@RequestParam(required = false) Long page,
                                                  @RequestParam(required = false) Long pageSize) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(detailService.miniappCourses(user, page, pageSize));
    }

    @Operation(summary = "课程详情（已发布版本）")
    @GetMapping("/{courseId}")
    public ApiResponse<MiniappCourseDetailVo> detail(@PathVariable long courseId) {
        CurrentUser user = CurrentUserHolder.require();
        return ApiResponse.ok(detailService.miniappDetail(user, courseId));
    }
}
