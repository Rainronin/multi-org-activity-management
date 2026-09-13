package com.example.activity.modules.ai;

import java.time.OffsetDateTime;

/**
 * 组织 AI 模型配置（P8.1）：每组织最多一条当前配置。
 *
 * <p>{@code version} 用于全部保存与启停的乐观锁；{@code connectionRevision} 只在协议、端点、模型、
 * 认证方式、密钥与调用参数变化时递增，测试成功绑定该版本；{@code testedRevision} 与当前版本不一致时
 * 不能启用，也不是可生成状态。密钥只以 AES-GCM 密文保存，读取 DTO 不回显。
 */
public class AiConfig {

    public static final String PROTOCOL_OPENAI = "OPENAI_CHAT_COMPLETIONS";
    public static final String PROTOCOL_ANTHROPIC = "ANTHROPIC_MESSAGES";
    public static final java.util.List<String> PROTOCOLS = java.util.List.of(PROTOCOL_OPENAI, PROTOCOL_ANTHROPIC);

    /** 认证方式：OpenAI 协议固定 Bearer；Anthropic 协议默认 x-api-key，兼容网关可显式选择 Bearer。 */
    public static final String AUTH_BEARER = "BEARER";
    public static final String AUTH_X_API_KEY = "X_API_KEY";

    /** 输出上限字段名：部分 OpenAI 兼容服务只接受 max_completion_tokens，由本次真实测试确认，不自动换参重发。 */
    public static final String LIMIT_MAX_TOKENS = "MAX_TOKENS";
    public static final String LIMIT_MAX_COMPLETION_TOKENS = "MAX_COMPLETION_TOKENS";

    private Long id;
    private Long orgId;
    private String protocol;
    private String endpoint;
    private String model;
    private String authScheme;
    private byte[] apiKeyCipher;
    private String apiKeyKeyId;
    private Integer maxOutputTokens;
    private String outputLimitField;
    private Integer timeoutSeconds;
    private boolean enabled;
    private Integer connectionRevision;
    private Integer testedRevision;
    private OffsetDateTime testedAt;
    private Long testedBy;
    private Long operatorAccountId;
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public boolean hasApiKey() {
        return apiKeyCipher != null && apiKeyCipher.length > 0;
    }

    /** 当前版本的测试是否通过（启用与生成的前置条件）。 */
    public boolean isTestedForCurrentRevision() {
        return testedRevision != null && testedRevision.equals(connectionRevision);
    }

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

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAuthScheme() {
        return authScheme;
    }

    public void setAuthScheme(String authScheme) {
        this.authScheme = authScheme;
    }

    public byte[] getApiKeyCipher() {
        return apiKeyCipher;
    }

    public void setApiKeyCipher(byte[] apiKeyCipher) {
        this.apiKeyCipher = apiKeyCipher;
    }

    public String getApiKeyKeyId() {
        return apiKeyKeyId;
    }

    public void setApiKeyKeyId(String apiKeyKeyId) {
        this.apiKeyKeyId = apiKeyKeyId;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getOutputLimitField() {
        return outputLimitField;
    }

    public void setOutputLimitField(String outputLimitField) {
        this.outputLimitField = outputLimitField;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getConnectionRevision() {
        return connectionRevision;
    }

    public void setConnectionRevision(Integer connectionRevision) {
        this.connectionRevision = connectionRevision;
    }

    public Integer getTestedRevision() {
        return testedRevision;
    }

    public void setTestedRevision(Integer testedRevision) {
        this.testedRevision = testedRevision;
    }

    public OffsetDateTime getTestedAt() {
        return testedAt;
    }

    public void setTestedAt(OffsetDateTime testedAt) {
        this.testedAt = testedAt;
    }

    public Long getTestedBy() {
        return testedBy;
    }

    public void setTestedBy(Long testedBy) {
        this.testedBy = testedBy;
    }

    public Long getOperatorAccountId() {
        return operatorAccountId;
    }

    public void setOperatorAccountId(Long operatorAccountId) {
        this.operatorAccountId = operatorAccountId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
