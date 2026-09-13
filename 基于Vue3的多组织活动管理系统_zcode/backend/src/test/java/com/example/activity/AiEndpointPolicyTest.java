package com.example.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.activity.common.ApiException;
import com.example.activity.modules.ai.AiEndpointPolicy;
import com.example.activity.modules.ai.AiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P8.1 外部地址边界（后端架构 6.4）：生产默认拒绝 http、非 443 端口、非清单域名，以及解析到
 * 回环、私网、链路本地与云元数据地址的目标；本机验证开关只放宽地址范围，不关闭结构与凭据校验。
 *
 * <p>用 localhost 做解析断言，不依赖外网 DNS；真实域名与端口的可用性由 P8.4 的真实调用验证。
 */
class AiEndpointPolicyTest {

    private static AiProperties productionProperties() {
        AiProperties properties = new AiProperties();
        properties.setAllowedHosts(List.of("localhost", "*.example.com"));
        properties.setAllowPrivateHosts(false);
        return properties;
    }

    private static AiProperties localProperties() {
        AiProperties properties = new AiProperties();
        properties.setAllowPrivateHosts(true);
        return properties;
    }

    @Test
    void rejectsNonHttpsAndMissingHost() {
        AiEndpointPolicy policy = new AiEndpointPolicy(productionProperties());
        assertThatThrownBy(() -> policy.validate("http://localhost/chat/completions"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("只允许 https");
        assertThatThrownBy(() -> policy.validate("ftp://localhost/x"))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("   "))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("https:///chat"))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsCredentialsInUrl() {
        AiEndpointPolicy policy = new AiEndpointPolicy(productionProperties());
        assertThatThrownBy(() -> policy.validate("https://user:pass@localhost/chat"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("API Key 字段");
        assertThatThrownBy(() -> policy.validate("https://localhost/chat#frag"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("# 之后");
        assertThatThrownBy(() -> policy.validate("https://localhost/chat?api_key=sk-live-123"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("API Key 字段");
    }

    @Test
    void rejectsHostsOutsideAllowlistAndPrivateAddresses() {
        AiEndpointPolicy policy = new AiEndpointPolicy(productionProperties());
        // 清单只允许 localhost 与 *.example.com：其它域名即使合法也不放行，新增网关由部署扩充清单
        assertThatThrownBy(() -> policy.validate("https://api.deepseek.com/chat/completions"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("模型域名");
        // 清单内但解析到回环地址：拒绝，避免配置指向内网或云元数据
        assertThatThrownBy(() -> policy.validate("https://localhost/chat"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("回环、私网、链路本地与云元数据地址一律拒绝");
        // 非 443 端口在保存与调用时同样拒绝
        assertThatThrownBy(() -> policy.validate("https://sub.example.com:8443/chat"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("443 端口");
    }

    @Test
    void acceptsAllowlistedPublicShapeAndLocalMode() {
        AiProperties local = localProperties();
        AiEndpointPolicy policy = new AiEndpointPolicy(local);
        AiEndpointPolicy.Target target = policy.validate("http://127.0.0.1:18080/v1/chat/completions");
        assertThat(target.host()).isEqualTo("127.0.0.1");
        assertThat(target.hostAndPort()).isEqualTo("127.0.0.1:18080");
        assertThat(target.uri().getPath()).isEqualTo("/v1/chat/completions");
    }
}
