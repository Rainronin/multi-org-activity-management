package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * 两种协议共用的发送与错误映射：读取响应体时有字节上限，状态码按可区分的 AI 错误码返回，
 * 错误文本先脱敏再展示（不包含密钥、便于排错但保留上游说明）。
 */
final class AiHttpSupport {

    record RawResponse(int status, String body, String upstreamRequestId, boolean tooLarge) {
    }

    private AiHttpSupport() {
    }

    static RawResponse send(HttpClient client, HttpRequest request, int maxResponseBytes) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String upstreamRequestId = response.headers().firstValue("x-request-id")
                .or(() -> response.headers().firstValue("request-id"))
                .orElse(null);
            String body;
            boolean tooLarge;
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(maxResponseBytes + 1);
                tooLarge = bytes.length > maxResponseBytes;
                int length = Math.min(bytes.length, maxResponseBytes);
                body = new String(bytes, 0, length, StandardCharsets.UTF_8);
            }
            return new RawResponse(response.statusCode(), body, upstreamRequestId, tooLarge);
        } catch (java.net.http.HttpTimeoutException ex) {
            throw problem(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT", "模型调用超时",
                "模型服务未在限定时间内返回，可稍后重试或先手工编写内容。");
        } catch (IOException ex) {
            throw problem(HttpStatus.BAD_GATEWAY, "AI_CONNECT_FAILED", "模型服务连接失败",
                "无法连接模型服务，请核对完整端点与网络后重试，手工编写不受影响。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "AI_CALL_INTERRUPTED", "模型调用被中断",
                "服务正在停止或请求被取消，请稍后重试。");
        }
    }

    /** 状态码映射：上游 401 只表示模型服务拒绝本次密钥，不映射为本系统登录失效。 */
    static void requireSuccess(RawResponse raw, String secret) {
        if (raw.tooLarge()) {
            throw problem(HttpStatus.BAD_GATEWAY, "AI_RESPONSE_TOO_LARGE", "模型响应过大",
                "上游返回的响应体超出限制，本次结果不可用，请改用手工编写或调整模型配置。");
        }
        if (raw.status() >= 200 && raw.status() < 300) {
            return;
        }
        String detail = snippet(raw.body(), secret);
        switch (raw.status()) {
            case 401, 403 -> throw problem(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_AUTH", "模型服务认证失败",
                "上游拒绝了本次密钥（HTTP " + raw.status() + "），请核对密钥与模型配置；本站登录状态不受影响。");
            case 404 -> throw problem(HttpStatus.BAD_GATEWAY, "AI_UNSUPPORTED_MODEL", "模型或端点不可用",
                "上游返回 404，请核对完整请求地址与模型 ID。" + detail);
            case 429 -> throw problem(HttpStatus.TOO_MANY_REQUESTS, "AI_UPSTREAM_RATE_LIMITED", "模型服务限流",
                "上游限流，请稍后再试或先手工编写。" + detail);
            default -> {
                if (raw.status() >= 500) {
                    throw problem(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", "模型服务异常",
                        "上游返回 HTTP " + raw.status() + "，请稍后重试。" + detail);
                }
                throw problem(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_REJECTED", "模型服务拒绝了本次请求",
                    "上游返回 HTTP " + raw.status() + "，请核对模型 ID、输出上限与调用参数。" + detail);
            }
        }
    }

    static JsonNode parse(ObjectMapper objectMapper, RawResponse raw) {
        if (raw.body() == null || raw.body().isBlank()) {
            throw invalidOutput("上游返回空响应");
        }
        try {
            return objectMapper.readTree(raw.body());
        } catch (IOException ex) {
            throw invalidOutput("上游响应不是可解析的 JSON");
        }
    }

    /** 上游在 2xx 中返回的结构化错误（部分网关如此），同样按失败处理。 */
    static void rejectEmbeddedError(JsonNode root, String secret, ObjectMapper objectMapper) {
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.isObject() ? error.path("message").asText(error.toString()) : error.asText();
            throw problem(HttpStatus.BAD_GATEWAY, "AI_UPSTREAM_ERROR", "模型服务返回错误",
                "上游说明：" + snippet(message, secret));
        }
    }

    static ApiException invalidOutput(String reason) {
        return problem(HttpStatus.BAD_GATEWAY, "AI_INVALID_OUTPUT", "模型输出不可用",
            reason + "，未生成可用草稿，可重试或改用手工编写。");
    }

    static ApiException truncated() {
        return problem(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_TRUNCATED", "模型输出被截断",
            "输出达到配置的长度上限，未生成完整草稿；可提高输出上限或简化输入后重试。");
    }

    private static ApiException problem(HttpStatus status, String code, String title, String detail) {
        return new ApiException(status, code, title, detail);
    }

    /** 脱敏后的上游说明：去掉密钥、控制字符与超长内容。 */
    static String snippet(String body, String secret) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String value = body;
        JsonNode parsed = null;
        try {
            parsed = new ObjectMapper().readTree(body);
        } catch (IOException ignored) {
            // 非 JSON 时按纯文本脱敏
        }
        if (parsed != null && parsed.isObject()) {
            JsonNode error = parsed.path("error");
            if (error.isObject() && error.hasNonNull("message")) {
                value = error.get("message").asText();
            } else if (parsed.hasNonNull("message")) {
                value = parsed.get("message").asText();
            }
        }
        if (secret != null && !secret.isBlank()) {
            value = value.replace(secret, "***");
        }
        value = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (value.length() > 200) {
            value = value.substring(0, 200) + "…";
        }
        return value.isEmpty() ? "" : " 上游说明：" + value;
    }

    static boolean isBearer(String authScheme) {
        return authScheme == null || "BEARER".equals(authScheme.toUpperCase(Locale.ROOT));
    }
}
