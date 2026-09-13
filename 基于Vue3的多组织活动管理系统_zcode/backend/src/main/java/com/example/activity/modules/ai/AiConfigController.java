package com.example.activity.modules.ai;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织 AI 模型配置端点（P8.1）：组织设置页承载该模块组件，organization 模块不保存第二份密钥。
 *
 * <p>读取、保存、测试、启用、停用与清除密钥都逐请求鉴权并受 CSRF 保护；密钥只从表单提交一次，
 * 查询只返回 hasApiKey，不回显原值或密文；浏览器不直连模型。
 */
@RestController
public class AiConfigController {

    public record ConfigResponse(
        String viewerRole, boolean available, String availabilityHint, String configState,
        String protocol, String model, String endpoint, String authScheme, Integer maxOutputTokens,
        String outputLimitField, Integer timeoutSeconds, boolean hasApiKey, boolean enabled, Long version,
        Integer connectionRevision, Integer testedRevision, boolean testedForCurrentRevision,
        OffsetDateTime testedAt, Long testedBy) {
    }

    public record SaveConfigRequest(
        @Pattern(regexp = "^(OPENAI_CHAT_COMPLETIONS|ANTHROPIC_MESSAGES)$", message = "未知的兼容协议") String protocol,
        @Size(max = 512, message = "请求地址不超过 512 字符") String endpoint,
        @Size(max = 128, message = "模型 ID 不超过 128 字符") String model,
        @Pattern(regexp = "^(BEARER|X_API_KEY)?$", message = "未知的认证方式") String authScheme,
        @Size(max = 400, message = "密钥不超过 400 字符") String apiKey,
        boolean clearApiKey,
        @Min(value = 64, message = "输出上限至少 64") @Max(value = 4096, message = "输出上限最多 4096")
        Integer maxOutputTokens,
        @Pattern(regexp = "^(MAX_TOKENS|MAX_COMPLETION_TOKENS)?$", message = "未知的输出上限字段") String outputLimitField,
        @Min(value = 5, message = "单次时限至少 5 秒") @Max(value = 60, message = "单次时限最多 60 秒")
        Integer timeoutSeconds,
        @Min(value = 0, message = "版本号不能为负") Long version) {
    }

    /** 版本自 0 起：必须与读取到的配置版本一致，缺失或不符由服务层判定并返回明确错误。 */
    public record VersionRequest(@Min(value = 0, message = "版本号不能为负") Long version) {
    }

    public record ConnectionTestResponse(
        String status, String message, Integer testedRevision, String requestId, OffsetDateTime testedAt) {
    }

    private final AiConfigService configService;
    private final AiGenerationService generationService;

    public AiConfigController(AiConfigService configService, AiGenerationService generationService) {
        this.configService = configService;
        this.generationService = generationService;
    }

    private static ConfigResponse toResponse(AiConfigService.AiConfigView view) {
        return new ConfigResponse(view.viewerRole(), view.available(), view.availabilityHint(), view.configState(),
            view.protocol(), view.model(), view.endpoint(), view.authScheme(), view.maxOutputTokens(),
            view.outputLimitField(), view.timeoutSeconds(), view.hasApiKey(), view.enabled(), view.version(),
            view.connectionRevision(), view.testedRevision(), view.testedForCurrentRevision(),
            view.testedAt(), view.testedBy());
    }

    @GetMapping("/api/organizations/{orgId}/ai/config")
    public ResponseEntity<ConfigResponse> get(
        @AuthenticationPrincipal AccountUserDetails principal, @PathVariable("orgId") Long orgId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(configService.get(principal.getAccountId(), orgId)));
    }

    @PutMapping("/api/organizations/{orgId}/ai/config")
    public ResponseEntity<ConfigResponse> save(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody SaveConfigRequest request) {
        AiConfigService.AiConfigView saved = configService.save(principal.getAccountId(), orgId,
            new AiConfigService.SaveCommand(request.protocol(), request.endpoint(), request.model(),
                request.authScheme(), request.apiKey(), request.clearApiKey(), request.maxOutputTokens(),
                request.outputLimitField(), request.timeoutSeconds(), request.version()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(saved));
    }

    @PostMapping("/api/organizations/{orgId}/ai/config/test")
    public ResponseEntity<ConnectionTestResponse> test(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody VersionRequest request) {
        AiGenerationService.ConnectionTestResult result =
            generationService.testConnection(principal.getAccountId(), orgId, request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ConnectionTestResponse(result.status(), result.message(), result.testedRevision(),
                result.requestId(), result.testedAt()));
    }

    @PostMapping("/api/organizations/{orgId}/ai/config/enable")
    public ResponseEntity<ConfigResponse> enable(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(configService.enable(principal.getAccountId(), orgId, request.version())));
    }

    @PostMapping("/api/organizations/{orgId}/ai/config/disable")
    public ResponseEntity<ConfigResponse> disable(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(configService.disable(principal.getAccountId(), orgId, request.version())));
    }

    @PostMapping("/api/organizations/{orgId}/ai/config/clear-key")
    public ResponseEntity<ConfigResponse> clearKey(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody VersionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(configService.clearKey(principal.getAccountId(), orgId, request.version())));
    }
}
