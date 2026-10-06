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
 * OpenAI Chat Completions 兼容适配：完整 POST 端点、Authorization: Bearer，
 * model＋messages、stream=false，输出上限字段可选 max_tokens 或 max_completion_tokens
 * （由本次真实测试确认，失败不自动换参数重发）。只取 choices 中的最终正文，忽略推理块。
 */
@Component
public class OpenAiChatCompletionsClient implements AiProtocolClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AiProperties properties;

    public OpenAiChatCompletionsClient(
        ObjectMapper objectMapper, HttpClient aiHttpClient, AiProperties properties) {
        this.objectMapper = objectMapper;
        this.httpClient = aiHttpClient;
        this.properties = properties;
    }

    @Override
    public String protocol() {
        return AiConfig.PROTOCOL_OPENAI;
    }

    @Override
    public Result call(Request request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", request.systemPrompt());
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.userPrompt());
        body.put("stream", false);
        // DeepSeek 默认启用思考模式，连接测试的 64 tokens 会耗在推理上；文稿草稿也无需推理块。
        if ("api.deepseek.com".equalsIgnoreCase(request.uri().getHost())) {
            body.putObject("thinking").put("type", "disabled");
        }
        String limitField = AiConfig.LIMIT_MAX_COMPLETION_TOKENS.equals(request.outputLimitField())
            ? "max_completion_tokens" : "max_tokens";
        body.put(limitField, request.maxOutputTokens());

        HttpRequest httpRequest = HttpRequest.newBuilder(request.uri())
            .timeout(Duration.ofSeconds(request.timeoutSeconds()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + request.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

        AiHttpSupport.RawResponse raw = AiHttpSupport.send(httpClient, httpRequest, properties.getMaxResponseBytes());
        AiHttpSupport.requireSuccess(raw, request.apiKey());
        JsonNode root = AiHttpSupport.parse(objectMapper, raw);
        AiHttpSupport.rejectEmbeddedError(root, request.apiKey(), objectMapper);

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw AiHttpSupport.invalidOutput("上游响应没有 choices 正文");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (message.hasNonNull("refusal") && !message.get("refusal").asText().isBlank()) {
            throw AiHttpSupport.invalidOutput("模型拒绝了本次生成请求");
        }
        String text = extractText(message.path("content"));
        if (text.isBlank()) {
            throw AiHttpSupport.invalidOutput("上游返回空正文");
        }
        if ("length".equals(choice.path("finish_reason").asText())) {
            throw AiHttpSupport.truncated();
        }
        return new Result(text.trim(), raw.upstreamRequestId());
    }

    /** 兼容 content 为字符串或分段数组两种形态；只拼接 type=text 的分段。 */
    private String extractText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText()) && part.hasNonNull("text")) {
                    builder.append(part.get("text").asText());
                }
            }
            return builder.toString();
        }
        return "";
    }
}
