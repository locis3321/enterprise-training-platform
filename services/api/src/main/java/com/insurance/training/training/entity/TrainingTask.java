package com.insurance.training.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 培训任务（表 training_task）。
 *
 * <p>{@code enterpriseId} 是任务下发目标；{@code packageVersionId} 必须是已发布的培训包版本，
 * 任务只引用不可变快照。时间窗左闭右开：{@code startAt <= now < learnDeadlineAt}。
 */
@TableName("training_task")
public class TrainingTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long insuranceCompanyId;
    private Long enterpriseId;
    private Long packageVersionId;
    private String title;
    private LocalDateTime startAt;
    private LocalDateTime learnDeadlineAt;
    private String status;
    private Long createdByAdminId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInsuranceCompanyId() {
        return insuranceCompanyId;
    }

    public void setInsuranceCompanyId(Long insuranceCompanyId) {
        this.insuranceCompanyId = insuranceCompanyId;
    }

    public Long getEnterpriseId() {
        return enterpriseId;
    }

    public void setEnterpriseId(Long enterpriseId) {
        this.enterpriseId = enterpriseId;
    }

    public Long getPackageVersionId() {
        return packageVersionId;
    }

    public void setPackageVersionId(Long packageVersionId) {
        this.packageVersionId = packageVersionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public void setStartAt(LocalDateTime startAt) {
        this.startAt = startAt;
    }

    public LocalDateTime getLearnDeadlineAt() {
        return learnDeadlineAt;
    }

    public void setLearnDeadlineAt(LocalDateTime learnDeadlineAt) {
        this.learnDeadlineAt = learnDeadlineAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedByAdminId() {
        return createdByAdminId;
    }

    public void setCreatedByAdminId(Long createdByAdminId) {
        this.createdByAdminId = createdByAdminId;
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