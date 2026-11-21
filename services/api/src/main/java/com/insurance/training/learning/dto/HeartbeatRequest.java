package com.insurance.training.learning.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 视频心跳。
 *
 * <p>职责有两个：
 * <ol>
 *   <li><b>证明正在播放</b>：周期上报，服务端据此把任务推进为「进行中」；</li>
 *   <li><b>声明播放完成</b>：{@code finished=true} 表示播放到结尾（ended 事件）。</li>
 * </ol>
 *
 * <p>{@code positionSeconds}/{@code clientTickMs}/{@code mediaDurationSeconds} 仅诊断，
 * 不参与任何判定——完成只看 {@code finished}。之所以能这样简化：
 * 播放器已禁用拖动，想触发 ended 就必须真的播完。
 */
public record HeartbeatRequest(
        @NotNull(message = "assignmentId 不能为空")
        Long assignmentId,

        @NotNull(message = "sectionId 不能为空")
        Long sectionId,

        @NotNull(message = "resourceId 不能为空")
        Long resourceId,

        Long positionSeconds,

        Long clientTickMs,

        Integer mediaDurationSeconds,

        /** 播放到结尾（ended 事件）时上报 true；服务端据此标记该视频完成，幂等。 */
        Boolean finished
) {
}
