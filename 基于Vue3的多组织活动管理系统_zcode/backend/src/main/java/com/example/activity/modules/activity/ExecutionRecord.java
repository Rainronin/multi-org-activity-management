package com.example.activity.modules.activity;

import java.time.OffsetDateTime;

/**
 * P6.1 执行记录：进展、问题、临时安排、实际差异与处理结果。
 * 只追加不修改——事后变化用新记录说明，不能把执行期的事后调整改写成事前已审批安排。
 */
public class ExecutionRecord {

    public static final String CATEGORY_PROGRESS = "PROGRESS";
    public static final String CATEGORY_ISSUE = "ISSUE";
    public static final String CATEGORY_ARRANGEMENT = "ARRANGEMENT";
    public static final String CATEGORY_DEVIATION = "DEVIATION";
    public static final String CATEGORY_HANDLING = "HANDLING";

    public static final java.util.List<String> CATEGORIES = java.util.List.of(
        CATEGORY_PROGRESS, CATEGORY_ISSUE, CATEGORY_ARRANGEMENT, CATEGORY_DEVIATION, CATEGORY_HANDLING);

    private Long id;
    private Long orgId;
    private Long activityId;
    private String category;
    private String content;
    private Long recordedBy;
    private String requestKey;
    private String requestFingerprint;
    private OffsetDateTime createdAt;

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(Long recordedBy) {
        this.recordedBy = recordedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
