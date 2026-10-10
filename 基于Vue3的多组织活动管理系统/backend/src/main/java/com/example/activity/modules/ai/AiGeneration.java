package com.example.activity.modules.ai;

import java.time.OffsetDateTime;

/**
 * AI 生成记录（P8.1）：与业务文稿分离，只保留可核对来源与结果，不保存密钥。
 *
 * <p>状态：RUNNING → SUCCEEDED／FAILED。重启或进程中断后遗留的 RUNNING 由超时核对转为 FAILED，
 * 不无限挂起；已返回的人工草稿不因记录状态变化被删除。CONNECTION_TEST 类型只用于连接测试，
 * 不关联活动，同样计入组织调用额度。
 */
public class AiGeneration {

    public static final String TYPE_SUMMARY = "ACTIVITY_SUMMARY";
    public static final String TYPE_PLAN = "ACTIVITY_PLAN";
    public static final String TYPE_CONNECTION_TEST = "CONNECTION_TEST";
    public static final java.util.List<String> TYPES = java.util.List.of(TYPE_SUMMARY, TYPE_PLAN);

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private Long orgId;
    private Long activityId;
    private String type;
    private Long operatorAccountId;
    private String status;
    private String requestKey;
    private String requestFingerprint;
    private String sourceHash;
    private String sourceScope;
    private String protocol;
    private String model;
    private String endpointHost;
    private Long configVersion;
    private Integer connectionRevision;
    private String outputText;
    private String errorCode;
    private String requestId;
    private OffsetDateTime adoptedAt;
    private Long adoptedBy;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getOperatorAccountId() {
        return operatorAccountId;
    }

    public void setOperatorAccountId(Long operatorAccountId) {
        this.operatorAccountId = operatorAccountId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public String getSourceScope() {
        return sourceScope;
    }

    public void setSourceScope(String sourceScope) {
        this.sourceScope = sourceScope;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEndpointHost() {
        return endpointHost;
    }

    public void setEndpointHost(String endpointHost) {
        this.endpointHost = endpointHost;
    }

    public Long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Long configVersion) {
        this.configVersion = configVersion;
    }

    public Integer getConnectionRevision() {
        return connectionRevision;
    }

    public void setConnectionRevision(Integer connectionRevision) {
        this.connectionRevision = connectionRevision;
    }

    public String getOutputText() {
        return outputText;
    }

    public void setOutputText(String outputText) {
        this.outputText = outputText;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public OffsetDateTime getAdoptedAt() {
        return adoptedAt;
    }

    public void setAdoptedAt(OffsetDateTime adoptedAt) {
        this.adoptedAt = adoptedAt;
    }

    public Long getAdoptedBy() {
        return adoptedBy;
    }

    public void setAdoptedBy(Long adoptedBy) {
        this.adoptedBy = adoptedBy;
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
