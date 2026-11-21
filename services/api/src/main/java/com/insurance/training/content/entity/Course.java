package com.insurance.training.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 课程（表 course），是版本的容器。
 *
 * <p>{@code insuranceCompanyId} 决定可见范围（同公司共享）；
 * {@code ownerEnterpriseId} 决定写权限归属（企业管理员只能改本企业创建的课程）。
 */
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long insuranceCompanyId;
    private Long ownerEnterpriseId;
    private String title;
    private String description;
    private Long coverResourceId;
    private String status;
    private Integer latestVersionNo;
    private Integer publishedVersionNo;
    private Long createdByAdminId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

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

    public Long getOwnerEnterpriseId() {
        return ownerEnterpriseId;
    }

    public void setOwnerEnterpriseId(Long ownerEnterpriseId) {
        this.ownerEnterpriseId = ownerEnterpriseId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCoverResourceId() {
        return coverResourceId;
    }

    public void setCoverResourceId(Long coverResourceId) {
        this.coverResourceId = coverResourceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getLatestVersionNo() {
        return latestVersionNo;
    }

    public void setLatestVersionNo(Integer latestVersionNo) {
        this.latestVersionNo = latestVersionNo;
    }

    public Integer getPublishedVersionNo() {
        return publishedVersionNo;
    }

    public void setPublishedVersionNo(Integer publishedVersionNo) {
        this.publishedVersionNo = publishedVersionNo;
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

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
