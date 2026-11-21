package com.insurance.training.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 版本—章节—资源绑定（表 course_version_resource）。
 *
 * <p>这是「引用」关系：同一 {@code resourceId} 可被多个版本绑定，
 * 因此版本复制（copy）只新增绑定行，不复制资源元数据与对象。
 */
@TableName("course_version_resource")
public class CourseVersionResource {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long courseVersionId;
    private Long sectionId;
    private Long resourceId;
    private Integer sortNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
