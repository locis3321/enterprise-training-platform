package com.insurance.training.learning.dto;

/**
 * 心跳结果。
 *
 * <p>{@code finishedRecorded} 表示本次是否把「该视频已播完」写入（repeated=true 时已存在）。
 * 不再有任何时长字段：完成判定只看是否播完。
 */
public record HeartbeatResponse(
        boolean finishedRecorded,
        /**
         * true 表示该视频尚未上报过时长，无法用服务端累计时长校验 finished，
         * 本次按客户端声称处理。补上时长后该标记消失。
         */
        boolean durationUnverified,
        String assignmentStatus,
        boolean completed
) {
}
