package com.example.activity.modules.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型调用共用的 HTTP 客户端：关闭重定向（禁止把密钥转发到另一个主机），连接超时由配置限定；
 * 单次请求时限在每个请求上按组织配置设置。
 */
@Configuration
public class AiHttpConfig {

    @Bean
    public HttpClient aiHttpClient(AiProperties properties) {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
            .build();
    }
}
