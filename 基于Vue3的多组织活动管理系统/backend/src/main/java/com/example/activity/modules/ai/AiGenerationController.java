package com.example.activity.modules.ai;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 生成端点（P8.2／P8.3）：属于活动资源，不暴露独立 AI 工作台。
 * 权限、状态与配置可用性在 Service 内判定；调用前先返回输入范围预览，调用后仍需人工编辑与保存。
 */
@RestController
public class AiGenerationController {

    public record InputPreviewResponse(
        boolean available, String availabilityHint, String sourceHash, List<String> scope, List<String> missing) {
    }

    public record PlanConditionsRequest(
        @Size(max = 200, message = "主题不超过 200 字符") String topic,
        @Size(max = 500, message = "目标不超过 500 字符") String goal,
        @Size(max = 200, message = "参与对象不超过 200 字符") String audience,
        @Min(value = 1, message = "预计人数至少 1") @Max(value = 100000, message = "预计人数最多 100000")
        Integer expectedParticipants,
        @Size(max = 200, message = "预算说明不超过 200 字符") String budget,
        @Size(max = 200, message = "时间地点条件不超过 200 字符") String timePlace,
        @Size(max = 500, message = "补充限制不超过 500 字符") String constraints) {
    }

    public record GenerateRequest(
        @Pattern(regexp = "^(ACTIVITY_SUMMARY|ACTIVITY_PLAN)$", message = "未知的生成类型") String type,
        @Valid PlanConditionsRequest conditions) {
    }

    public record GenerationResponse(
        String generationId, String type, String status, String activityId, String output, String errorCode,
        String errorMessage, String protocol, String model, String endpointHost, List<String> scope,
        List<String> missing, boolean sourceChanged, OffsetDateTime createdAt, OffsetDateTime adoptedAt,
        String adoptedBy, String requestId) {
    }

    public record GenerationListResponse(List<GenerationResponse> items) {
    }

    private final AiGenerationService generationService;

    public AiGenerationController(AiGenerationService generationService) {
        this.generationService = generationService;
    }

    private static GenerationResponse toResponse(AiGenerationService.GenerationView view) {
        return new GenerationResponse(view.generationId(), view.type(), view.status(), view.activityId(),
            view.output(), view.errorCode(), view.errorMessage(), view.protocol(), view.model(),
            view.endpointHost(), view.scope(), view.missing(), view.sourceChanged(), view.createdAt(),
            view.adoptedAt(), view.adoptedBy() == null ? null : String.valueOf(view.adoptedBy()), view.requestId());
    }

    /** 生成前预览本次输入范围与缺失信息，不调用模型。 */
    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/ai/input")
    public ResponseEntity<InputPreviewResponse> preview(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam("type") String type) {
        AiGenerationService.InputPreview preview =
            generationService.preview(principal.getAccountId(), orgId, activityId, type);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new InputPreviewResponse(preview.available(), preview.availabilityHint(), preview.sourceHash(),
                preview.scope(), preview.missing()));
    }

    /** 生成：必须携带 Idempotency-Key；同键同内容重放只返回既有记录，不重复调用模型。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/ai/generations")
    public ResponseEntity<GenerationResponse> generate(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody GenerateRequest request) {
        String key = idempotencyKey == null ? null : idempotencyKey.trim();
        AiGenerationService.GenerationView result;
        if ("ACTIVITY_PLAN".equals(request.type())) {
            PlanConditionsRequest conditions = request.conditions();
            AiGenerationService.PlanConditions value = conditions == null
                ? new AiGenerationService.PlanConditions(null, null, null, null, null, null, null)
                : new AiGenerationService.PlanConditions(conditions.topic(), conditions.goal(),
                    conditions.audience(), conditions.expectedParticipants(), conditions.budget(),
                    conditions.timePlace(), conditions.constraints());
            result = generationService.generatePlan(principal.getAccountId(), orgId, activityId, value, key);
        } else {
            result = generationService.generateSummary(principal.getAccountId(), orgId, activityId, key);
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(toResponse(result));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/ai/generations")
    public ResponseEntity<GenerationListResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam("type") String type) {
        List<GenerationResponse> items =
            generationService.list(principal.getAccountId(), orgId, activityId, type).stream()
                .map(AiGenerationController::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new GenerationListResponse(items));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/ai/generations/by-request-key")
    public ResponseEntity<GenerationResponse> getByRequestKey(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam("key") String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(generationService.getByRequestKey(principal.getAccountId(), orgId, activityId, key)));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/ai/generations/{generationId}")
    public ResponseEntity<GenerationResponse> get(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("generationId") Long generationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(generationService.get(principal.getAccountId(), orgId, activityId, generationId)));
    }

    /** 人工采用草稿：只记录采用状态，保存方案／总结仍走所属业务端点。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/ai/generations/{generationId}/adopt")
    public ResponseEntity<GenerationResponse> adopt(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("generationId") Long generationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(generationService.adopt(principal.getAccountId(), orgId, activityId, generationId)));
    }
}
