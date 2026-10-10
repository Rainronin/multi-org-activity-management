package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.organization.OrganizationMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织 AI 模型配置（P8.1，计划书 7.4）：每组织一套当前配置，活动总结与方案共用。
 *
 * <p>只有组织负责人可查看配置详情、保存、测试、启用或清除密钥；活动负责人与普通成员只读取
 * 是否可用及模型显示信息。保存校验版本；密钥不传表示保留，替换必须传新值，清除单独表达；
 * 修改协议或地址必须重新输入密钥，旧密钥不允许被静默发送到新地址。
 */
@Service
public class AiConfigService {

    /** 配置详情：负责人可见全部字段；非负责人只保留可用状态与模型显示信息。 */
    public record AiConfigView(
        String viewerRole,
        boolean available,
        String availabilityHint,
        String configState,
        String protocol,
        String model,
        String endpoint,
        String authScheme,
        Integer maxOutputTokens,
        String outputLimitField,
        Integer timeoutSeconds,
        boolean hasApiKey,
        boolean enabled,
        Long version,
        Integer connectionRevision,
        Integer testedRevision,
        boolean testedForCurrentRevision,
        java.time.OffsetDateTime testedAt,
        Long testedBy) {
    }

    public record SaveCommand(
        String protocol,
        String endpoint,
        String model,
        String authScheme,
        String apiKey,
        boolean clearApiKey,
        Integer maxOutputTokens,
        String outputLimitField,
        Integer timeoutSeconds,
        Long version) {
    }

    private final AiConfigMapper configMapper;
    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final AiKeyCipher keyCipher;
    private final AiEndpointPolicy endpointPolicy;
    private final AiProperties properties;
    private final AuditService auditService;

    public AiConfigService(
        AiConfigMapper configMapper,
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        AiKeyCipher keyCipher,
        AiEndpointPolicy endpointPolicy,
        AiProperties properties,
        AuditService auditService) {
        this.configMapper = configMapper;
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.keyCipher = keyCipher;
        this.endpointPolicy = endpointPolicy;
        this.properties = properties;
        this.auditService = auditService;
    }

    /** 读取配置：负责人得详情，其他有效成员只得可用状态与模型显示信息。 */
    public AiConfigView get(Long accountId, Long orgId) {
        OrganizationMember member = requireActiveMember(orgId, accountId);
        AiConfig config = configMapper.findByOrgId(orgId);
        return view(config, OrganizationMember.ROLE_OWNER.equals(member.getRole()));
    }

    /** 保存配置：首次保存不带版本，之后必须携带期望版本；地址与协议变化必须重新输入密钥。 */
    @Transactional
    public AiConfigView save(Long accountId, Long orgId, SaveCommand command) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        AiConfig existing = configMapper.findByOrgId(orgId);
        if (existing == null && command.version() != null && command.version() != 0L) {
            throw conflict("EDIT_CONFLICT", "配置已被他人修改", "该组织尚无配置，请刷新后重新保存。");
        }
        if (existing != null && command.version() == null) {
            throw fieldError("version", "必须携带配置版本号");
        }
        if (existing != null && !existing.getVersion().equals(command.version())) {
            throw conflict("EDIT_CONFLICT", "配置已被他人修改", "配置版本已变化，请刷新后重试。");
        }
        String protocol = requireProtocol(command.protocol());
        String authScheme = requireAuthScheme(protocol, command.authScheme());
        String outputLimitField = requireOutputLimitField(command.outputLimitField());
        int maxOutputTokens = bounded(command.maxOutputTokens(), properties.getDefaultMaxOutputTokens(),
            properties.getMinOutputTokens(), properties.getMaxOutputTokens(), "maxOutputTokens", "输出上限");
        int timeoutSeconds = bounded(command.timeoutSeconds(), properties.getDefaultTimeoutSeconds(),
            5, properties.getMaxTimeoutSeconds(), "timeoutSeconds", "单次时限");
        if (command.model() == null || command.model().isBlank()) {
            throw fieldError("model", "请填写模型 ID");
        }
        String model = command.model().trim();
        if (model.length() > 128) {
            throw fieldError("model", "模型 ID 不超过 128 字符");
        }
        String endpoint = command.endpoint() == null ? "" : command.endpoint().trim();
        String endpointHost = endpointPolicy.validate(endpoint).hostAndPort();
        boolean keyProvided = command.apiKey() != null && !command.apiKey().isBlank();
        if (command.clearApiKey() && keyProvided) {
            throw fieldError("apiKey", "不能同时替换与清除密钥");
        }
        boolean endpointOrProtocolChanged = existing == null
            || !existing.getProtocol().equals(protocol) || !existing.getEndpoint().equals(endpoint);
        if (existing != null && endpointOrProtocolChanged && !keyProvided) {
            throw fieldError("apiKey", "修改协议或请求地址必须重新输入密钥");
        }
        boolean revisionChanged = existing == null
            || endpointOrProtocolChanged
            || !existing.getModel().equals(model)
            || !existing.getAuthScheme().equals(authScheme)
            || existing.getMaxOutputTokens() != maxOutputTokens
            || !existing.getOutputLimitField().equals(outputLimitField)
            || existing.getTimeoutSeconds() != timeoutSeconds
            || keyProvided
            || command.clearApiKey();

        AiConfig target = new AiConfig();
        target.setOrgId(orgId);
        target.setProtocol(protocol);
        target.setEndpoint(endpoint);
        target.setModel(model);
        target.setAuthScheme(authScheme);
        target.setMaxOutputTokens(maxOutputTokens);
        target.setOutputLimitField(outputLimitField);
        target.setTimeoutSeconds(timeoutSeconds);
        target.setOperatorAccountId(accountId);
        // 保存即生效；无密钥或明确清除密钥时保持不可用，保存不调用厂商。
        target.setEnabled(!command.clearApiKey() && (keyProvided || (existing != null && existing.hasApiKey())));
        if (keyProvided) {
            target.setApiKeyCipher(keyCipher.encrypt(orgId, command.apiKey().trim()));
            target.setApiKeyKeyId(keyCipher.currentKeyId());
        }
        if (existing == null) {
            target.setConnectionRevision(1);
            configMapper.insert(target);
        } else {
            target.setVersion(existing.getVersion());
            target.setConnectionRevision(existing.getConnectionRevision() + (revisionChanged ? 1 : 0));
            // 配置变化清空旧诊断结果，不能将旧测试标记到新配置。
            if (!revisionChanged) {
                target.setTestedRevision(existing.getTestedRevision());
                target.setTestedAt(existing.getTestedAt());
                target.setTestedBy(existing.getTestedBy());
            }
            if (configMapper.update(target, command.clearApiKey()) != 1) {
                throw conflict("EDIT_CONFLICT", "配置已被他人修改", "配置版本已变化，请刷新后重试。");
            }
        }
        auditService.recordChange(accountId, "AI", "AI_CONFIG_SAVED", "org:" + orgId, null,
            Map.of("protocol", protocol, "model", model, "endpointHost", endpointHost,
                "connectionRevision", target.getConnectionRevision(), "revisionChanged", revisionChanged,
                "apiKeyConfigured", keyProvided || (existing != null && existing.hasApiKey() && !command.clearApiKey())), auditState(existing), auditState(configMapper.findByOrgId(orgId)));
        return view(configMapper.findByOrgId(orgId), true);
    }

    /** 恢复已停用配置：校验当前版本与密钥，连接测试为可选诊断。 */
    @Transactional
    public AiConfigView enable(Long accountId, Long orgId, Long version) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        AiConfig config = requireConfig(orgId);
        requireVersion(config, version);
        if (!config.hasApiKey()) {
            throw conflict("AI_KEY_MISSING", "尚未配置密钥", "请先填写并保存密钥。");
        }
        if (configMapper.setEnabled(orgId, true, accountId, version) != 1) {
            throw conflict("EDIT_CONFLICT", "配置已被他人修改", "配置版本已变化，请刷新后重试。");
        }
        auditService.recordChange(accountId, "AI", "AI_CONFIG_ENABLED", "org:" + orgId, null,
            Map.of("connectionRevision", config.getConnectionRevision()), auditState(config), auditState(configMapper.findByOrgId(orgId)));
        return view(requireConfig(orgId), true);
    }

    /** 停用：保留已保存配置与已返回的人工草稿，只是不再调用模型。 */
    @Transactional
    public AiConfigView disable(Long accountId, Long orgId, Long version) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        AiConfig config = requireConfig(orgId);
        requireVersion(config, version);
        if (configMapper.setEnabled(orgId, false, accountId, version) != 1) {
            throw conflict("EDIT_CONFLICT", "配置已被他人修改", "配置版本已变化，请刷新后重试。");
        }
        auditService.recordChange(accountId, "AI", "AI_CONFIG_DISABLED", "org:" + orgId, null, null, auditState(config), auditState(configMapper.findByOrgId(orgId)));
        return view(requireConfig(orgId), true);
    }

    /** 清除密钥：同时停用并递增 revision，旧测试状态一并失效。 */
    @Transactional
    public AiConfigView clearKey(Long accountId, Long orgId, Long version) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        AiConfig config = requireConfig(orgId);
        requireVersion(config, version);
        if (configMapper.clearKey(orgId, config.getConnectionRevision() + 1, accountId, version) != 1) {
            throw conflict("EDIT_CONFLICT", "配置已被他人修改", "配置版本已变化，请刷新后重试。");
        }
        auditService.recordChange(accountId, "AI", "AI_KEY_CLEARED", "org:" + orgId, null, null, auditState(config), auditState(configMapper.findByOrgId(orgId)));
        return view(requireConfig(orgId), true);
    }

    /** 连接测试前置：短事务内取组织共享锁，校验负责人、期望版本与密钥是否齐备。 */
    @Transactional
    public AiConfig requireTestable(Long accountId, Long orgId, Long version) {
        organizationMapper.lockOrganizationShared(orgId);
        requireOwner(orgId, accountId);
        AiConfig config = requireConfig(orgId);
        requireVersion(config, version);
        if (!config.hasApiKey()) {
            throw conflict("AI_KEY_MISSING", "尚未配置密钥", "请先填写并保存密钥，再测试连接。");
        }
        return config;
    }

    /** 生成的前置条件：已配置、已启用且有密钥；连接测试不作为开关。 */
    public AiConfig requireUsable(Long orgId) {
        AiConfig config = configMapper.findByOrgId(orgId);
        if (config == null) {
            throw conflict("AI_NOT_CONFIGURED", "尚未配置模型",
                "请先在「组织设置 → AI 模型」保存配置；方案与总结仍可手工编写。");
        }
        if (!config.hasApiKey()) {
            throw conflict("AI_KEY_MISSING", "尚未配置密钥", "请在组织设置中填写并保存密钥。");
        }
        if (!config.isEnabled()) {
            throw conflict("AI_NOT_ENABLED", "模型配置未启用",
                "请在「组织设置 → AI 模型」中启用当前配置；方案与总结仍可手工编写。");
        }
        return config;
    }

    public AiConfig requireConfig(Long orgId) {
        AiConfig config = configMapper.findByOrgId(orgId);
        if (config == null) {
            throw conflict("AI_NOT_CONFIGURED", "尚未配置模型", "请先保存本组织的模型配置。");
        }
        return config;
    }

    static record ConfigState(Long version, Integer connectionRevision, Integer testedRevision, boolean enabled, boolean hasApiKey) { }

    static ConfigState auditState(AiConfig config) {
        return config == null ? null : new ConfigState(config.getVersion(), config.getConnectionRevision(), config.getTestedRevision(), config.isEnabled(), config.hasApiKey());
    }

    private AiConfigView view(AiConfig config, boolean owner) {
        if (config == null) {
            return new AiConfigView(owner ? "OWNER" : "MEMBER", false,
                "尚未配置模型，方案与总结可手工编写", "NOT_CONFIGURED",
                null, null, null, null, null, null, null, false, false, null, null, null, false, null, null);
        }
        boolean available = config.isEnabled() && config.hasApiKey();
        String state = !config.hasApiKey() ? "NEEDS_KEY"
            : config.isEnabled() ? "ENABLED" : "DISABLED";
        String hint = switch (state) {
            case "NEEDS_KEY" -> "尚未配置密钥";
            case "DISABLED" -> "模型配置已停用，可继续手工编写";
            default -> "可用：" + config.getModel();
        };
        if (!owner) {
            // 活动负责人与普通成员只读取可用状态与模型显示信息，不返回地址、参数与版本细节
            return new AiConfigView("MEMBER", available, hint, state, config.getProtocol(),
                config.getModel(), null, null, null, null, null, config.hasApiKey(), config.isEnabled(),
                null, null, null, config.isTestedForCurrentRevision(), null, null);
        }
        return new AiConfigView("OWNER", available, hint, state, config.getProtocol(), config.getModel(),
            config.getEndpoint(), config.getAuthScheme(), config.getMaxOutputTokens(),
            config.getOutputLimitField(), config.getTimeoutSeconds(), config.hasApiKey(), config.isEnabled(),
            config.getVersion(), config.getConnectionRevision(), config.getTestedRevision(),
            config.isTestedForCurrentRevision(), config.getTestedAt(), config.getTestedBy());
    }

    private String requireProtocol(String protocol) {
        if (protocol == null || !AiConfig.PROTOCOLS.contains(protocol.trim())) {
            throw fieldError("protocol", "协议必须是 OPENAI_CHAT_COMPLETIONS 或 ANTHROPIC_MESSAGES");
        }
        return protocol.trim();
    }

    private String requireAuthScheme(String protocol, String authScheme) {
        String scheme = authScheme == null || authScheme.isBlank() ? null : authScheme.trim();
        if (AiConfig.PROTOCOL_OPENAI.equals(protocol)) {
            if (scheme != null && !AiConfig.AUTH_BEARER.equals(scheme)) {
                throw fieldError("authScheme", "OpenAI 兼容协议固定使用 Bearer 认证");
            }
            return AiConfig.AUTH_BEARER;
        }
        if (scheme == null) {
            return AiConfig.AUTH_X_API_KEY;
        }
        if (!AiConfig.AUTH_X_API_KEY.equals(scheme) && !AiConfig.AUTH_BEARER.equals(scheme)) {
            throw fieldError("authScheme", "Anthropic 协议的认证方式必须是 X_API_KEY 或 BEARER");
        }
        return scheme;
    }

    private String requireOutputLimitField(String field) {
        if (field == null || field.isBlank()) {
            return AiConfig.LIMIT_MAX_TOKENS;
        }
        String value = field.trim();
        if (!AiConfig.LIMIT_MAX_TOKENS.equals(value) && !AiConfig.LIMIT_MAX_COMPLETION_TOKENS.equals(value)) {
            throw fieldError("outputLimitField", "输出上限字段必须是 MAX_TOKENS 或 MAX_COMPLETION_TOKENS");
        }
        return value;
    }

    private int bounded(Integer value, int fallback, int min, int max, String field, String label) {
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            throw fieldError(field, label + "必须在 " + min + "～" + max + " 之间");
        }
        return value;
    }

    private void requireVersion(AiConfig config, Long version) {
        if (version == null) {
            throw fieldError("version", "必须携带配置版本号");
        }
        if (!config.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "配置已被他人修改", "配置版本已变化，请刷新后重试。");
        }
    }

    private OrganizationMember requireActiveMember(Long orgId, Long accountId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        if (member == null) {
            throw notFound();
        }
        return member;
    }

    private void requireOwner(Long orgId, Long accountId) {
        OrganizationMember member = requireActiveMember(orgId, accountId);
        if (!OrganizationMember.ROLE_OWNER.equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以管理 AI 模型配置。");
        }
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效", "请求字段校验失败，请修改后重试。",
            List.of(new com.example.activity.common.FieldErrorItem(field, message)));
    }
}
