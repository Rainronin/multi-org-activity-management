package com.example.activity.modules.notification;

import java.time.OffsetDateTime;

/** 站内提示意图：来源操作、接收账号、类型与资源引用；生成即可读，重复来源被唯一键挡下。 */
public class Notification {

    public static final String TYPE_TASK_ASSIGNED = "TASK_ASSIGNED";
    public static final String TYPE_REGISTRATION_APPROVED = "REGISTRATION_APPROVED";
    public static final String TYPE_REGISTRATION_REJECTED = "REGISTRATION_REJECTED";
    public static final String TYPE_REGISTRATION_CANCELLED = "REGISTRATION_CANCELLED";
    public static final String TYPE_REGISTRATION_CANCELLED_SELF = "REGISTRATION_CANCELLED_SELF";
    public static final String TYPE_ACTIVITY_RECONFIRMED = "ACTIVITY_RECONFIRMED";
    public static final String TYPE_ACTIVITY_CANCELLED = "ACTIVITY_CANCELLED";
    public static final String TYPE_EXPENSE_SUBMITTED = "EXPENSE_SUBMITTED";
    public static final String TYPE_EXPENSE_APPROVED = "EXPENSE_APPROVED";
    public static final String TYPE_EXPENSE_REJECTED = "EXPENSE_REJECTED";

    private Long id;
    private Long recipientAccountId;
    private String type;
    private Long activityId;
    private String sourceKey;
    private String payload;
    private OffsetDateTime createdAt;
    private OffsetDateTime readAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecipientAccountId() {
        return recipientAccountId;
    }

    public void setRecipientAccountId(Long recipientAccountId) {
        this.recipientAccountId = recipientAccountId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(OffsetDateTime readAt) {
        this.readAt = readAt;
    }
}
