package com.example.activity.modules.ai;

import java.net.URI;

/**
 * 兼容协议适配（后端架构 6.4）：只建立两种必要协议，共用 Java HTTP、超时、错误与文本结果结构；
 * 服务商名称只是显示信息，不据此分派业务。不开放任意请求头或任意 JSON 模板，
 * 也不支持 Responses、工具执行、图片、流式输出或模型自动发现。
 */
public interface AiProtocolClient {

    /** 一次文本调用所需的全部参数；密钥只在内存中传递，不进入生成记录。 */
    record Request(
        URI uri,
        String endpointLabel,
        String model,
        String apiKey,
        String authScheme,
        int timeoutSeconds,
        int maxOutputTokens,
        String outputLimitField,
        String systemPrompt,
        String userPrompt) {
    }

    /** 最终正文与上游追踪标识；空正文、拒绝或截断不当作成功结果。 */
    record Result(String text, String upstreamRequestId) {
    }

    String protocol();

    Result call(Request request);
}
