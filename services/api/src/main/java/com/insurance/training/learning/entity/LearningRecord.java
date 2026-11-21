package com.insurance.training.learning.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 视频学习记录（表 learning_record）。
 *
 * <p>完成判定只看 {@code completed}：视频播放到结尾（ended 事件）即置 1。
 * 之所以能用「播完」代替「看了多少秒」，是因为播放器已禁用拖动
 * （{@code controls=false} / {@code enable-progress-gesture=false}），
 * 想播完就必须真的播完。
 *
 * <p>{@code effectiveSeconds} 由服务端时钟累加（单章节按视频时长封顶），
 * 用于校验客户端 finished 是否可信、以及管理端查看学习投入；完成判定仍只看 {@code completed}。
 */
@TableName("learning_record")
public class LearningRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long assignmentId;
    private Long courseVersionId;
    private Long sectionId;
    private Long resourceId;
    private Integer effectiveSeconds;
    private Integer completed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public Long getCourseVersionId() {
        return courseVersionId;
    }

    public void setCourseVersionId(Long courseVersionId) {
        this.courseVersionId = courseVersionId;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public void setSectionId(Long sectionId) {
        this.sectionId = sectionId;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public Integer getEffectiveSeconds() {
        return effectiveSeconds;
    }

    public Integer getCompleted() {
        return completed;
    }

    public void setCompleted(Integer completed) {
        this.completed = completed;
    }

    public void setEffectiveSeconds(Integer effectiveSeconds) {
        this.effectiveSeconds = effectiveSeconds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}