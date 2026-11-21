package com.insurance.training.training.vo;

import java.time.LocalDateTime;

/**
 * 任务内单个员工的分配明细。
 *
 * <p>{@code effectiveSeconds} 是该 assignment 下所有视频明细之和（服务端累计），
 * 不是客户端上报值。
 */
public record TaskAssignmentVo(
        long assignmentId,
        long employeeId,
        String name,
        String status,
        int effectiveSeconds,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}