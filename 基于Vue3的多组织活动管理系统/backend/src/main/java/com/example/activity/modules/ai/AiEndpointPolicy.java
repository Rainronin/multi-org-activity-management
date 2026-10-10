package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 外部地址边界（后端架构 6.4）：完整端点仅允许 HTTPS、合法公网域名与部署允许的端口，
 * 禁止 userinfo、片段与携带密钥的 query；拒绝回环、私网、链路本地与云元数据地址。
 *
 * <p>保存与调用共用同一校验，且每次调用前都重新解析域名后再校验解析结果，
 * 因此保存后被改成内网地址也会在调用前被拒绝。真正的 DNS 重绑定防护还需要部署侧的受限出口
 * （只允许访问清单内域名与地址段），该要求记录在部署说明中，此处不声称一次 DNS 检查已足够。
 * 客户端关闭重定向，禁止把密钥转发到另一个主机。
 *
 * <p>{@code app.ai.allow-private-hosts} 仅供本机与自动化验证使用，默认关闭。
 */
@Component
public class AiEndpointPolicy {

    /** 校验后的调用目标：保存端点标识时不带密钥与 query。 */
    public record Target(URI uri, String host, int port, String hostAndPort) {
    }

    private static final List<String> FORBIDDEN_QUERY_KEYS = List.of(
        "key", "api_key", "apikey", "api-key", "token", "access_token", "authorization", "secret", "password");

    private final AiProperties properties;

    public AiEndpointPolicy(AiProperties properties) {
        this.properties = properties;
    }

    public Target validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw rejected("请填写完整的模型请求地址", "字段 endpoint 不能为空。");
        }
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException ex) {
            throw rejected("模型请求地址格式不正确", "请填写完整端点，例如 https://api.deepseek.com/chat/completions。");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean localMode = properties.isAllowPrivateHosts();
        if (!"https".equals(scheme) && !(localMode && "http".equals(scheme))) {
            throw rejected("模型请求地址必须使用 HTTPS", "只允许 https 完整端点，不接受 http 或其它协议。");
        }
        if (uri.getUserInfo() != null) {
            throw rejected("模型请求地址不允许携带用户名或密码", "请把凭据放在 API Key 字段，不要写进地址。");
        }
        if (uri.getFragment() != null) {
            throw rejected("模型请求地址不允许携带片段", "请去掉地址中 # 之后的内容。");
        }
        rejectCredentialQuery(uri);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw rejected("模型请求地址缺少主机名", "请填写完整端点。");
        }
        host = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (!localMode) {
            if (!hostAllowed(host)) {
                throw rejected("模型请求地址不在允许访问的域名清单内",
                    "本部署只允许访问清单内的模型域名，新增可信网关需由部署配置扩充。");
            }
            if (port != 443) {
                throw rejected("模型请求地址端口不允许", "只允许 443 端口的标准 HTTPS 端点。");
            }
            requirePublicAddress(host);
        }
        return new Target(uri, host, port, host + ":" + port);
    }

    private void rejectCredentialQuery(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (String key : FORBIDDEN_QUERY_KEYS) {
            if (lower.contains(key + "=")) {
                throw rejected("模型请求地址不允许在 query 中携带凭据",
                    "请把密钥放在 API Key 字段，地址只保留端点路径。");
            }
        }
    }

    private boolean hostAllowed(String host) {
        for (String allowed : properties.getAllowedHosts()) {
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            String candidate = allowed.trim().toLowerCase(Locale.ROOT);
            if (candidate.startsWith("*.")) {
                String suffix = candidate.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (candidate.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** 解析结果全部必须是公网单播地址；只要有一个私网地址就拒绝，避免部分解析被引到内网。 */
    private void requirePublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw rejected("模型请求地址无法解析", "请核对域名是否正确，或稍后重试。");
        }
        if (addresses.length == 0) {
            throw rejected("模型请求地址无法解析", "域名没有可用的解析结果。");
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocalIpv6(address)
                || isSharedAddressSpace(address)) {
                throw rejected("模型请求地址指向内网地址",
                    "回环、私网、链路本地与云元数据地址一律拒绝，请填写公网端点。");
            }
        }
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** 100.64.0.0/10 运营商级 NAT 段，同样不当作公网目标。 */
    private boolean isSharedAddressSpace(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private ApiException rejected(String title, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "AI_ENDPOINT_REJECTED", title, detail);
    }
}
