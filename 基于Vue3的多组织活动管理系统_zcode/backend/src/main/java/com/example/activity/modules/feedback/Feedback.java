package com.example.activity.modules.feedback;

import java.time.OffsetDateTime;

/**
 * P6.3 反馈：固定 1～5 分总体评分与文字建议，每账号每活动一份。
 * 是否可提交由 {@code feedback_eligibility} 中的资格依据加当前身份校验决定，不在此实体判断。
 */
public class Feedback {

    public static final String SOURCE_REGISTRATION_APPROVED = "REGISTRATION_APPROVED";
    public static final String SOURCE_ACTIVITY_OWNER = "ACTIVITY_OWNER";
    public static final String SOURCE_TASK_ASSIGNEE = "TASK_ASSIGNEE";

    private Long id;
    private Long orgId;
    private Long activityId;
    private Long accountId;
    private Integer rating;
    private String comment;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
