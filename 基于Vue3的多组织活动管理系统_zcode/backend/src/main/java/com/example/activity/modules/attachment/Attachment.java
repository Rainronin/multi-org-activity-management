package com.example.activity.modules.attachment;

import java.time.OffsetDateTime;

/** 附件元数据；内容在服务端受控目录，随机存储名。 */
public class Attachment {

    public static final String PURPOSE_ACTIVITY_DOC = "ACTIVITY_DOC";
    public static final String PURPOSE_TASK_ATTACHMENT = "TASK_ATTACHMENT";

    private Long id;
    private Long orgId;
    private Long activityId;
    /** S10：任务资料时指向任务；活动资料为 null（二选一，后端架构 6.1）。 */
    private Long taskId;
    private String purpose;
    private String storedName;
    private String originalName;
    private String contentType;
    private Long sizeBytes;
    private Long uploadedBy;
    /** F06：上传意图的 Idempotency-Key（按组织唯一），仅创建时写入。 */
    private String requestKey;
    /** F06：上传请求指纹（文件内容摘要 + 归属），重放只与此比较。 */
    private String requestFingerprint;
    private OffsetDateTime createdAt;
    private OffsetDateTime deletedAt;

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
