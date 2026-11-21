package com.insurance.training.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 培训包版本—课程版本关联（表 package_course）。
 *
 * <p>指向具体 {@code course_version.id} 而非课程，保证包快照不受课程改版影响。
 * 仅 DRAFT 包版本可增删本表行。
 */
@TableName("package_course")
public class PackageCourse {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long packageVersionId;
    private Long courseVersionId;
    private Integer sortNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPackageVersionId() {
        return packageVersionId;
    }

    public void setPackageVersionId(Long packageVersionId) {
        this.packageVersionId = packageVersionId;
    }

    public Long getCourseVersionId() {
        return courseVersionId;
    }

    public void setCourseVersionId(Long courseVersionId) {
        this.courseVersionId = courseVersionId;
    }

    public Integer getSortNo() {
        return sortNo;
    }

    public void setSortNo(Integer sortNo) {
        this.sortNo = sortNo;
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