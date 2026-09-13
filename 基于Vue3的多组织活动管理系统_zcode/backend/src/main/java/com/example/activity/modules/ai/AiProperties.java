package com.example.activity.modules.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 调用限额与部署参数（后端架构 6.4／7）。全部有界：
 *
 * <ul>
 *   <li>模型总开关 {@code app.ai.enabled} 关闭或未配置组织模型时，核心业务与手工方案／总结继续可用；</li>
 *   <li>单实例并发门控只限制正在进行的调用，不为限流搭建完整分布式平台；</li>
 *   <li>组织额度按持久生成记录计算（含连接测试），跨重启仍有效；</li>
 *   <li>输入长度、响应体大小、单次时限与输出上限都有配置上限，具体值结合选定模型调整。</li>
 * </ul>
 *
 * <p>主密钥由部署环境提供（{@code ACTIVITY_AI_MASTER_KEY} 或本机配置），不进数据库与仓库；
 * 缺失时只有 AI 配置报错，不影响其他组织与手工业务。
 */
@Component
@ConfigurationProperties("app.ai")
public class AiProperties {

    /** 部署级模型总开关：关闭时所有 AI 入口返回“服务未配置”，不加载假结果。 */
    private boolean enabled = true;

    /** 主加密密钥（Base64 编码的 32 字节 AES 密钥）；部署环境保管，可为空表示未提供。 */
    private String masterKey = "";

    /** 主密钥标识：随密文保存，便于轮换与定位。 */
    private String masterKeyId = "local-v1";

    private int maxConcurrentCalls = 2;

    private int maxCallsPerDayPerOrg = 50;

    private int maxCallsPerFiveMinutesPerOrg = 10;

    /** 单次发送给模型的输入字符上限（含资料与提示词）。 */
    private int maxInputChars = 8000;

    /** 上游响应体字节上限，超出即失败，不把超大响应读入内存。 */
    private int maxResponseBytes = 262144;

    /** 单次草稿入库字符上限。 */
    private int maxDraftChars = 20000;

    private int connectTimeoutSeconds = 5;

    private int defaultTimeoutSeconds = 20;

    private int maxTimeoutSeconds = 60;

    private int minOutputTokens = 64;

    private int defaultMaxOutputTokens = 1024;

    private int maxOutputTokens = 4096;

    /** 连接测试：固定短文本 + 有界输出，只证明当次基础调用可用。 */
    private int testMaxOutputTokens = 64;

    private int testTimeoutSeconds = 15;

    /**
     * 允许访问的模型域名清单（部署维护；支持 {@code *.example.com} 通配后缀）。
     * 首期覆盖文档列出的兼容服务，新增可信网关时由部署配置扩充。
     */
    private java.util.List<String> allowedHosts = new java.util.ArrayList<>(java.util.List.of(
        "api.deepseek.com", "open.bigmodel.cn", "api.moonshot.cn", "api.anthropic.com"));

    /**
     * 仅用于本机与测试环境：允许 http、回环／私网地址与任意端口。
     * 生产默认关闭，回环、私网、链路本地与云元数据地址一律拒绝。
     */
    private boolean allowPrivateHosts = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public String getMasterKeyId() {
        return masterKeyId;
    }

    public void setMasterKeyId(String masterKeyId) {
        this.masterKeyId = masterKeyId;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public int getMaxCallsPerDayPerOrg() {
        return maxCallsPerDayPerOrg;
    }

    public void setMaxCallsPerDayPerOrg(int maxCallsPerDayPerOrg) {
        this.maxCallsPerDayPerOrg = maxCallsPerDayPerOrg;
    }

    public int getMaxCallsPerFiveMinutesPerOrg() {
        return maxCallsPerFiveMinutesPerOrg;
    }

    public void setMaxCallsPerFiveMinutesPerOrg(int maxCallsPerFiveMinutesPerOrg) {
        this.maxCallsPerFiveMinutesPerOrg = maxCallsPerFiveMinutesPerOrg;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getMaxDraftChars() {
        return maxDraftChars;
    }

    public void setMaxDraftChars(int maxDraftChars) {
        this.maxDraftChars = maxDraftChars;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public int getMaxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public int getMinOutputTokens() {
        return minOutputTokens;
    }

    public void setMinOutputTokens(int minOutputTokens) {
        this.minOutputTokens = minOutputTokens;
    }

    public int getDefaultMaxOutputTokens() {
        return defaultMaxOutputTokens;
    }

    public void setDefaultMaxOutputTokens(int defaultMaxOutputTokens) {
        this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getTestMaxOutputTokens() {
        return testMaxOutputTokens;
    }

    public void setTestMaxOutputTokens(int testMaxOutputTokens) {
        this.testMaxOutputTokens = testMaxOutputTokens;
    }

    public int getTestTimeoutSeconds() {
        return testTimeoutSeconds;
    }

    public void setTestTimeoutSeconds(int testTimeoutSeconds) {
        this.testTimeoutSeconds = testTimeoutSeconds;
    }

    public java.util.List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(java.util.List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public boolean isAllowPrivateHosts() {
        return allowPrivateHosts;
    }

    public void setAllowPrivateHosts(boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }
}
