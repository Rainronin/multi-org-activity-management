package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 模型调用编排（后端架构 6.3）：校验端点与配置 → 解密密钥 → 按协议适配调用 → 返回文本。
 * 本类不开启事务：调用发生在业务短事务之外，避免长时间持锁。
 *
 * <p>请求快照只保留非敏感的协议、模型、配置版本与端点标识，不保存密钥；返回结果附带本次
 * 调用的 requestId（仅用于追踪单次 HTTP 请求，不作为防重键或结果恢复依据）。
 */
@Component
public class AiModelClient {

    public record Outcome(
        String text,
        String upstreamRequestId,
        String requestId,
        String protocol,
        String model,
        String endpointHost,
        Long configVersion,
        Integer connectionRevision) {
    }

    private final AiProperties properties;
    private final AiEndpointPolicy endpointPolicy;
    private final AiKeyCipher keyCipher;
    private final AiCallGate gate;
    private final Map<String, AiProtocolClient> clients;

    public AiModelClient(
        AiProperties properties,
        AiEndpointPolicy endpointPolicy,
        AiKeyCipher keyCipher,
        AiCallGate gate,
        List<AiProtocolClient> protocolClients) {
        this.properties = properties;
        this.endpointPolicy = endpointPolicy;
        this.keyCipher = keyCipher;
        this.gate = gate;
        Map<String, AiProtocolClient> byProtocol = new HashMap<>();
        for (AiProtocolClient client : protocolClients) {
            byProtocol.put(client.protocol(), client);
        }
        this.clients = Map.copyOf(byProtocol);
    }

    /** 保存配置时的地址校验：与调用共用同一策略，返回可供记录的端点标识。 */
    public String validateEndpoint(String endpoint) {
        return endpointPolicy.validate(endpoint).hostAndPort();
    }

    /** 连接测试与生成共用同一入口；{@code connectionTest} 只影响证据文本、输出上限与时限。 */
    public Outcome call(AiConfig config, boolean connectionTest, String systemPrompt, String userPrompt) {
        int inputLength = systemPrompt.length() + userPrompt.length();
        if (inputLength > properties.getMaxInputChars()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_INPUT_TOO_LONG", "输入资料过长",
                "本次输入 " + inputLength + " 字符，超过上限 " + properties.getMaxInputChars()
                    + "；请缩减条件或先归档无关内容后重试。");
        }
        AiProtocolClient client = clients.get(config.getProtocol());
        if (client == null) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_NOT_CONFIGURED", "模型配置不可用",
                "当前配置的协议不受支持，请在组织设置中重新选择协议并重新测试。");
        }
        gate.acquire(config.getOrgId());
        try {
            AiEndpointPolicy.Target target = endpointPolicy.validate(config.getEndpoint());
            String apiKey = keyCipher.decrypt(config.getOrgId(), config.getApiKeyCipher(), config.getApiKeyKeyId());
            int timeoutSeconds = connectionTest
                ? Math.min(config.getTimeoutSeconds(), properties.getTestTimeoutSeconds())
                : config.getTimeoutSeconds();
            int maxOutputTokens = connectionTest
                ? properties.getTestMaxOutputTokens() : config.getMaxOutputTokens();
            AiProtocolClient.Result result = client.call(new AiProtocolClient.Request(
                target.uri(), target.hostAndPort(), config.getModel(), apiKey, config.getAuthScheme(),
                timeoutSeconds, maxOutputTokens, config.getOutputLimitField(), systemPrompt, userPrompt));
            String text = result.text() == null ? "" : result.text().trim();
            if (text.isEmpty()) {
                throw AiHttpSupport.invalidOutput("模型返回空正文");
            }
            if (text.length() > properties.getMaxDraftChars()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_TOO_LONG", "模型输出过长",
                    "本次输出 " + text.length() + " 字符，超过单次草稿上限 " + properties.getMaxDraftChars()
                        + "，未生成可用草稿；请缩减输入后重试或改用手工编写。");
            }
            return new Outcome(text, result.upstreamRequestId(), MDC.get("requestId"), config.getProtocol(),
                config.getModel(), target.hostAndPort(), config.getVersion(), config.getConnectionRevision());
        } finally {
            gate.release();
        }
    }
}
