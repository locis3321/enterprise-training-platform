package com.insurance.training.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 非视频资源首次打开登记（表 resource_open_log，append-only、幂等）。
 *
 * <p>与视频心跳互斥：视频走心跳计时，资料走「已打开」这个弱信号。
 * 该表**不推进** {@code training_assignment.last_heartbeat_at}。
 */
@TableName("resource_open_log")
public class ResourceOpenLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assignmentId;
    private Long resourceId;
    private LocalDateTime openedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(Long assignmentId) {
        this.assignmentId = assignmentId;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(LocalDateTime openedAt) {
        this.openedAt = openedAt;
    }
}