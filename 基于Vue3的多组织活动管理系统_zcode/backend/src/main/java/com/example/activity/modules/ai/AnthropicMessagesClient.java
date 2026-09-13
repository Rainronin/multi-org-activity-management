package com.example.activity.modules.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Anthropic Messages 兼容适配：完整 POST 端点，默认 {@code x-api-key}（兼容网关可在配置中显式选择 Bearer），
 * {@code anthropic-version} 固定为适配器支持的版本；system 与 messages 分离、max_tokens 必填、stream=false；
 * 按顺序合并 content 中的 text 块。
 */
@Component
public class AnthropicMessagesClient implements AiProtocolClient {

    /** 适配器支持的 Messages 版本，不随配置变化。 */
    static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AiProperties properties;

    public AnthropicMessagesClient(ObjectMapper objectMapper, HttpClient aiHttpClient, AiProperties properties) {
        this.objectMapper = objectMapper;
        this.httpClient = aiHttpClient;
        this.properties = properties;
    }

    @Override
    public String protocol() {
        return AiConfig.PROTOCOL_ANTHROPIC;
    }

    @Override
    public Result call(Request request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxOutputTokens());
        body.put("system", request.systemPrompt());
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userPrompt());
        body.put("stream", false);

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
            .timeout(Duration.ofSeconds(request.timeoutSeconds()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("anthropic-version", ANTHROPIC_VERSION);
        if (AiHttpSupport.isBearer(request.authScheme())) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        } else {
            builder.header("x-api-key", request.apiKey());
        }

        AiHttpSupport.RawResponse raw = AiHttpSupport.send(
            httpClient, builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(),
            properties.getMaxResponseBytes());
        AiHttpSupport.requireSuccess(raw, request.apiKey());
        JsonNode root = AiHttpSupport.parse(objectMapper, raw);
        AiHttpSupport.rejectEmbeddedError(root, request.apiKey(), objectMapper);

        String stopReason = root.path("stop_reason").asText("");
        if ("refusal".equals(stopReason)) {
            throw AiHttpSupport.invalidOutput("模型拒绝了本次生成请求");
        }
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            throw AiHttpSupport.invalidOutput("上游响应没有 content 文本块");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                text.append(block.get("text").asText());
            }
        }
        if (text.toString().isBlank()) {
            throw AiHttpSupport.invalidOutput("上游返回空正文");
        }
        if ("max_tokens".equals(stopReason)) {
            throw AiHttpSupport.truncated();
        }
        return new Result(text.toString().trim(), raw.upstreamRequestId());
    }
}
