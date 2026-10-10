package com.example.activity.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P8 模型服务桩（JDK 内置 HttpServer）：只在本机测试中使用，用于验证两种兼容协议的真实 HTTP 调用、
 * 错误映射与调用前后行为。它不实现任何模型能力，返回固定短文本，不能替代真实模型验收。
 */
public final class StubModelServer implements AutoCloseable {

    /** 响应模式：覆盖正常、认证失败、空正文、截断、非法 JSON 与超时。 */
    public enum Mode {
        OK, UNAUTHORIZED, EMPTY, TRUNCATED, INVALID_JSON, TIMEOUT
    }

    public record Received(String path, String body, Map<String, String> headers) {

        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private final HttpServer server;
    private final List<Received> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Mode mode = Mode.OK;
    private volatile String shape = "openai";
    private volatile long delayMillis;
    private volatile Runnable onRequest;

    public StubModelServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("无法启动模型桩服务", ex);
        }
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    /** 完整端点示例：由测试按协议拼接端点路径。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 选择响应体形态：openai（choices）或 anthropic（content 文本块）。 */
    public void setShape(String shape) {
        this.shape = shape;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    /** 请求到达后、响应前执行（用于模拟调用期间配置或权限变化）。 */
    public void setOnRequest(Runnable onRequest) {
        this.onRequest = onRequest;
    }

    public void reset() {
        requests.clear();
        mode = Mode.OK;
        shape = "openai";
        delayMillis = 0;
        onRequest = null;
    }

    /** 只清空调用记录，保留当前模式与响应形态（同一用例内多段断言时使用）。 */
    public void clearRequests() {
        requests.clear();
    }

    public int callCount() {
        return requests.size();
    }

    public Received lastRequest() {
        synchronized (requests) {
            return requests.isEmpty() ? null : requests.get(requests.size() - 1);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
            headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
        requests.add(new Received(exchange.getRequestURI().getPath(), body, headers));
        Runnable hook = onRequest;
        if (hook != null) {
            hook.run();
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        boolean anthropic = "anthropic".equals(shape);
        switch (mode) {
            case UNAUTHORIZED -> respond(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
            case EMPTY -> respond(exchange, 200, anthropic
                ? "{\"content\":[],\"stop_reason\":\"end_turn\"}"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}");
            case TRUNCATED -> respond(exchange, 200, anthropic
                ? "{\"content\":[{\"type\":\"text\",\"text\":\"半截\"}],\"stop_reason\":\"max_tokens\"}"
                : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"半截\"},"
                    + "\"finish_reason\":\"length\"}]}");
            case INVALID_JSON -> respond(exchange, 200, "not-json-at-all");
            case TIMEOUT -> {
                // 延时后仍返回正常结构，用于验证客户端按配置时限放弃本次调用
                respond(exchange, 200, okBody(anthropic));
            }
            case OK -> respond(exchange, 200, okBody(anthropic));
            default -> respond(exchange, 200, "{}");
        }
    }

    private String okBody(boolean anthropic) {
        return anthropic
            ? "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"推理块应被忽略\"},"
                + "{\"type\":\"text\",\"text\":\"【桩模型】方案草稿正文\\n一、方案概述：待补充\"}],"
                + "\"stop_reason\":\"end_turn\"}"
            : "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"【桩模型】总结草稿正文\\n一、活动目标：待补充\"},\"finish_reason\":\"stop\"}]}";
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
