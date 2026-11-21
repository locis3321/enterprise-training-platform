package com.insurance.training.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建/修改课程。
 *
 * <p>归属规则：
 * <ul>
 *   <li>{@code insuranceCompanyId} 只对平台超级管理员有效（超管跨公司建课）；其他角色传入将被忽略；</li>
 *   <li>归属企业永远由服务端按当前用户决定，客户端不可指定。</li>
 * </ul>
 */
public record CourseRequest(
        @NotBlank(message = "课程标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "简介长度不能超过 2000")
        String description,

        /** 仅超级管理员使用：指定课程归属的集团。 */
        Long insuranceCompanyId
) {
}
