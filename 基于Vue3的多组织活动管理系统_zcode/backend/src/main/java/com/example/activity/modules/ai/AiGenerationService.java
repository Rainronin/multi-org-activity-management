package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.ActivityService;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 生成编排（P8.1，后端架构 6.3）：短事务取授权输入与来源版本并记录请求 → 事务外调用模型 →
 * 短事务复核权限、活动状态与配置版本后保存结果。
 *
 * <p>生成记录与业务文稿分离：本服务不写活动方案或总结，也不写确认状态；客户端采用草稿后仍走
 * 活动的方案保存与总结确认端点（版本与来源校验在那里执行）。模型失败使用统一错误码并保留人工内容；
 * 不自动重试、不换用新配置补发；同防重键重放只返回既有记录且重新鉴权。
 */
@Service
public class AiGenerationService {

    /** 方案生成条件：全部可选，缺失项由模型标注假设或待补充。 */
    public record PlanConditions(
        String topic, String goal, String audience, Integer expectedParticipants,
        String budget, String timePlace, String constraints) {
    }

    /** 生成记录视图：正文、错误、输入范围、缺失项与来源变化标记。 */
    public record GenerationView(
        String generationId,
        String type,
        String status,
        String activityId,
        String output,
        String errorCode,
        String errorMessage,
        String protocol,
        String model,
        String endpointHost,
        List<String> scope,
        List<String> missing,
        boolean sourceChanged,
        OffsetDateTime createdAt,
        OffsetDateTime adoptedAt,
        Long adoptedBy,
        String requestId) {
    }

    /** 生成前的输入范围预览：不调用模型，用户先确认会发送哪些资料。 */
    public record InputPreview(
        boolean available, String availabilityHint, String sourceHash, List<String> scope, List<String> missing) {
    }

    public record ConnectionTestResult(
        String status, String message, Integer testedRevision, String requestId, OffsetDateTime testedAt) {
    }

    private final AiGenerationMapper generationMapper;
    private final AiConfigMapper configMapper;
    private final AiConfigService configService;
    private final AiModelClient modelClient;
    private final ActivityService activityService;
    private final OrganizationMemberMapper memberMapper;
    private final AuditService auditService;
    private final AiProperties properties;
    private final TransactionTemplate transactionTemplate;

    public AiGenerationService(
        AiGenerationMapper generationMapper,
        AiConfigMapper configMapper,
        AiConfigService configService,
        AiModelClient modelClient,
        ActivityService activityService,
        OrganizationMemberMapper memberMapper,
        AuditService auditService,
        AiProperties properties,
        PlatformTransactionManager transactionManager) {
        this.generationMapper = generationMapper;
        this.configMapper = configMapper;
        this.configService = configService;
        this.modelClient = modelClient;
        this.activityService = activityService;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ---------- 输入范围预览 ----------

    /** P8.2：复盘页在生成前预览本次输入范围与缺失信息（不调用模型、不计额度）。 */
    public InputPreview preview(Long accountId, Long orgId, Long activityId, String type) {
        ActivityService.AiInput input = activityService.aiInput(accountId, orgId, activityId, requireType(type));
        AiConfig config = configMapper.findByOrgId(orgId);
        boolean available = config != null && config.isEnabled() && config.hasApiKey()
            && config.isTestedForCurrentRevision();
        String hint = available ? "可用：" + config.getModel()
            : config == null ? "尚未配置模型，可继续手工编写"
            : !config.hasApiKey() ? "尚未配置密钥"
            : !config.isTestedForCurrentRevision() ? "当前配置尚未通过连接测试"
            : "模型配置已停用，可继续手工编写";
        return new InputPreview(available, hint, input.sourceHash(), input.scope(), input.missing());
    }

    // ---------- 生成 ----------

    /** P8.2：活动总结生成。 */
    public GenerationView generateSummary(Long accountId, Long orgId, Long activityId, String requestKey) {
        return generate(accountId, orgId, activityId, AiGeneration.TYPE_SUMMARY, null, requestKey);
    }

    /** P8.3：活动方案生成。 */
    public GenerationView generatePlan(
        Long accountId, Long orgId, Long activityId, PlanConditions conditions, String requestKey) {
        return generate(accountId, orgId, activityId, AiGeneration.TYPE_PLAN, conditions, requestKey);
    }

    private GenerationView generate(
        Long accountId, Long orgId, Long activityId, String type, PlanConditions conditions, String requestKey) {
        requireRequestKey(requestKey);
        String conditionsText = AiGeneration.TYPE_PLAN.equals(type) ? renderConditions(conditions) : null;
        Snapshot snapshot = transactionTemplate.execute(status -> {
            // 1) 授权的输入投影与来源版本（含活动状态与身份校验）
            ActivityService.AiInput input = activityService.aiInput(accountId, orgId, activityId, type);
            // 2) 防重键：同组织同操作者同键只调用一次模型；内容不同的重放明确拒绝
            String fingerprint = sha256(type + "|" + activityId + "|" + input.sourceHash() + "|"
                + (conditionsText == null ? "" : conditionsText));
            AiGeneration existing = generationMapper.findByRequestKey(orgId, accountId, requestKey);
            if (existing != null) {
                if (!existing.getRequestFingerprint().equals(fingerprint)) {
                    throw conflict("IDEMPOTENCY_KEY_REUSED", "请求键已用于不同内容",
                        "该 Idempotency-Key 已用于内容不同的生成请求，请使用新的键重试。");
                }
                return new Snapshot(existing, input, null, true);
            }
            // 3) 当前可用配置快照（协议、模型、版本与端点标识；不含密钥）
            AiConfig config = configService.requireUsable(orgId);
            AiGeneration record = new AiGeneration();
            record.setOrgId(orgId);
            record.setActivityId(activityId);
            record.setType(type);
            record.setOperatorAccountId(accountId);
            record.setStatus(AiGeneration.STATUS_RUNNING);
            record.setRequestKey(requestKey);
            record.setRequestFingerprint(fingerprint);
            record.setSourceHash(input.sourceHash());
            record.setSourceScope(joinScope(input.scope()));
            record.setProtocol(config.getProtocol());
            record.setModel(config.getModel());
            record.setEndpointHost(modelClient.validateEndpoint(config.getEndpoint()));
            record.setConfigVersion(config.getVersion());
            record.setConnectionRevision(config.getConnectionRevision());
            try {
                generationMapper.insert(record);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw conflict("REQUEST_IN_PROGRESS", "相同请求已被受理", "请使用原请求键重试，以获取已有生成结果。");
            }
            return new Snapshot(record, input, config, false);
        });

        Long generationId = snapshot.record().getId();
        if (snapshot.replay()) {
            reconcileStaleRunning(orgId);
            AiGeneration row = generationMapper.findById(generationId);
            return view(row, snapshot.input());
        }
        AiPromptBuilder.Prompt prompt = AiGeneration.TYPE_SUMMARY.equals(type)
            ? AiPromptBuilder.summary(snapshot.input().facts(), snapshot.input().missing())
            : AiPromptBuilder.plan(conditionsText, snapshot.input().facts());
        AiModelClient.Outcome outcome;
        try {
            outcome = modelClient.call(snapshot.config(), false, prompt.system(), prompt.user());
        } catch (ApiException ex) {
            markFailed(generationId, ex.getCode());
            throw ex;
        }
        Finalize result = finalizeSuccess(accountId, orgId, activityId, type, generationId, outcome);
        if (result.error() != null) {
            throw result.error();
        }
        return result.view();
    }

    // ---------- 连接测试（P8.1） ----------

    /** 使用固定非业务短文本测试已保存的指定配置版本；测试同样计入组织调用额度。 */
    public ConnectionTestResult testConnection(Long accountId, Long orgId, Long version) {
        AiConfig config = configService.requireTestable(accountId, orgId, version);
        AiPromptBuilder.Prompt prompt = AiPromptBuilder.connectionTest();
        String requestKey = "test-" + UUID.randomUUID();
        AiGeneration record = transactionTemplate.execute(status -> {
            AiGeneration row = new AiGeneration();
            row.setOrgId(orgId);
            row.setActivityId(null);
            row.setType(AiGeneration.TYPE_CONNECTION_TEST);
            row.setOperatorAccountId(accountId);
            row.setStatus(AiGeneration.STATUS_RUNNING);
            row.setRequestKey(requestKey);
            row.setRequestFingerprint(sha256("CONNECTION_TEST|" + config.getConnectionRevision()));
            row.setSourceScope("连接测试：固定短文本，不发送任何组织资料");
            row.setProtocol(config.getProtocol());
            row.setModel(config.getModel());
            row.setEndpointHost(modelClient.validateEndpoint(config.getEndpoint()));
            row.setConfigVersion(config.getVersion());
            row.setConnectionRevision(config.getConnectionRevision());
            generationMapper.insert(row);
            return row;
        });
        AiModelClient.Outcome outcome;
        try {
            outcome = modelClient.call(config, true, prompt.system(), prompt.user());
        } catch (ApiException ex) {
            markFailed(record.getId(), ex.getCode());
            throw ex;
        }
        Finalize result = finalizeTest(accountId, orgId, config, record.getId(), outcome);
        if (result.error() != null) {
            throw result.error();
        }
        AiConfig latest = configMapper.findByOrgId(orgId);
        return new ConnectionTestResult("SUCCEEDED",
            "连接成功；测试通过只证明当次基础文本调用可用，启用后即可在活动中生成。",
            latest.getTestedRevision(), outcome.requestId(), latest.getTestedAt());
    }

    // ---------- 查询与人工采用 ----------

    /** 单条生成记录：重新鉴权后返回；撤权或活动状态不再允许时按不可见处理。 */
    public GenerationView get(Long accountId, Long orgId, Long activityId, Long generationId) {
        AiGeneration row = requireRecord(orgId, activityId, generationId);
        ActivityService.AiInput input = activityService.aiInput(accountId, orgId, activityId, requireType(row.getType()));
        reconcileStaleRunning(orgId);
        return view(generationMapper.findById(generationId), input);
    }

    /** 活动下最近的生成记录：复盘页与草稿页展示生成中／失败／成功与采用状态。 */
    public List<GenerationView> list(Long accountId, Long orgId, Long activityId, String type) {
        ActivityService.AiInput input = activityService.aiInput(accountId, orgId, activityId, requireType(type));
        reconcileStaleRunning(orgId);
        return generationMapper.listRecent(activityId, type, 5).stream().map(row -> view(row, input)).toList();
    }

    /** 人工采用草稿：只记录采用人与时间；保存方案／总结仍由所属业务端点执行。 */
    public GenerationView adopt(Long accountId, Long orgId, Long activityId, Long generationId) {
        AiGeneration row = requireRecord(orgId, activityId, generationId);
        ActivityService.AiInput input = activityService.aiInput(accountId, orgId, activityId, requireType(row.getType()));
        if (!AiGeneration.STATUS_SUCCEEDED.equals(row.getStatus()) || row.getOutputText() == null) {
            throw conflict("AI_GENERATION_NOT_READY", "该草稿不可采用", "只有生成成功的草稿可以记录采用状态。");
        }
        generationMapper.markAdopted(generationId, accountId);
        auditService.record(accountId, "AI", "AI_DRAFT_ADOPTED", "activity:" + activityId, null,
            Map.of("type", row.getType(), "generationId", String.valueOf(generationId)));
        return view(generationMapper.findById(generationId), input);
    }

    // ---------- 内部 ----------

    private record Snapshot(AiGeneration record, ActivityService.AiInput input, AiConfig config, boolean replay) {
    }

    private record Finalize(GenerationView view, ApiException error) {
    }

    /**
     * 成功收尾（短事务）：复核权限、活动状态与配置版本后再写结果。
     * 复核失败时在同一事务内把记录标为失败并保留核对依据，事务提交后再抛出错误，
     * 避免「看到草稿却没有有效业务文稿」的中间态。
     */
    private Finalize finalizeSuccess(
        Long accountId, Long orgId, Long activityId, String type, Long generationId, AiModelClient.Outcome outcome) {
        return transactionTemplate.execute(status -> {
            ActivityService.AiInput current;
            try {
                current = activityService.aiInput(accountId, orgId, activityId, type);
            } catch (ApiException ex) {
                generationMapper.finish(generationId, AiGeneration.STATUS_FAILED, outcome.text(),
                    "AI_PERMISSION_REVOKED", outcome.requestId());
                auditService.record(accountId, "AI", "AI_GENERATION_REVOKED", "activity:" + activityId, null,
                    Map.of("type", type, "reason", ex.getCode(), "generationId", String.valueOf(generationId)));
                return new Finalize(null, ex);
            }
            AiConfig latest = configMapper.findByOrgId(orgId);
            boolean configUnchanged = latest != null && latest.isEnabled()
                && latest.getConnectionRevision().equals(outcome.connectionRevision());
            if (!configUnchanged) {
                generationMapper.finish(generationId, AiGeneration.STATUS_FAILED, outcome.text(),
                    "AI_CONFIG_CHANGED", outcome.requestId());
                return new Finalize(null, conflict("AI_CONFIG_CHANGED", "调用期间模型配置已变化",
                    "本次结果未采用，也未自动改用新配置重发；如需草稿请按当前配置重新生成。"));
            }
            generationMapper.finish(generationId, AiGeneration.STATUS_SUCCEEDED, outcome.text(), null,
                outcome.requestId());
            auditService.record(accountId, "AI", "AI_GENERATION_SUCCEEDED", "activity:" + activityId, null,
                Map.of("type", type, "model", outcome.model(), "protocol", outcome.protocol(),
                    "chars", outcome.text().length(), "generationId", String.valueOf(generationId)));
            return new Finalize(view(generationMapper.findById(generationId), current), null);
        });
    }

    /** 连接测试收尾（短事务）：版本已变化或负责人已变化时不标记测试通过。 */
    private Finalize finalizeTest(
        Long accountId, Long orgId, AiConfig tested, Long generationId, AiModelClient.Outcome outcome) {
        return transactionTemplate.execute(status -> {
            AiConfig latest = configMapper.findByOrgId(orgId);
            if (latest == null || !latest.getConnectionRevision().equals(tested.getConnectionRevision())) {
                generationMapper.finish(generationId, AiGeneration.STATUS_FAILED, null,
                    "AI_CONFIG_CHANGED", outcome.requestId());
                return new Finalize(null, conflict("AI_CONFIG_CHANGED", "测试期间配置已变化",
                    "本次测试结果不生效，也不会把新配置标记为已通过；请对当前版本重新测试。"));
            }
            OrganizationMember member = memberMapper.findActive(orgId, accountId);
            if (member == null || !OrganizationMember.ROLE_OWNER.equals(member.getRole())) {
                generationMapper.finish(generationId, AiGeneration.STATUS_FAILED, null,
                    "AI_PERMISSION_REVOKED", outcome.requestId());
                return new Finalize(null, new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问",
                    "仅组织负责人可以测试模型配置。"));
            }
            if (configMapper.markTested(orgId, tested.getConnectionRevision(), accountId) != 1) {
                generationMapper.finish(generationId, AiGeneration.STATUS_FAILED, null,
                    "AI_CONFIG_CHANGED", outcome.requestId());
                return new Finalize(null, conflict("AI_CONFIG_CHANGED", "测试期间配置已变化",
                    "本次测试结果不生效；请对当前版本重新测试。"));
            }
            generationMapper.finish(generationId, AiGeneration.STATUS_SUCCEEDED, outcome.text(), null,
                outcome.requestId());
            auditService.record(accountId, "AI", "AI_CONNECTION_TESTED", "org:" + orgId, null,
                Map.of("protocol", outcome.protocol(), "model", outcome.model(),
                    "connectionRevision", outcome.connectionRevision()));
            return new Finalize(null, null);
        });
    }

    private void markFailed(Long generationId, String errorCode) {
        transactionTemplate.execute(status -> {
            generationMapper.finish(generationId, AiGeneration.STATUS_FAILED, null, errorCode, MDC.get("requestId"));
            return null;
        });
    }

    /**
     * 遗留 RUNNING 核对：进程中断或超时后不会无限挂起，超过单次时限的记录转为明确失败。
     */
    private void reconcileStaleRunning(Long orgId) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(properties.getMaxTimeoutSeconds() + 15L);
        transactionTemplate.execute(status -> {
            generationMapper.failStaleRunning(orgId, cutoff, "AI_CALL_ABORTED");
            return null;
        });
    }

    private GenerationView view(AiGeneration row, ActivityService.AiInput current) {
        boolean sourceChanged = row.getSourceHash() != null && current != null
            && !row.getSourceHash().equals(current.sourceHash());
        return new GenerationView(
            String.valueOf(row.getId()), row.getType(), row.getStatus(),
            row.getActivityId() == null ? null : String.valueOf(row.getActivityId()),
            row.getOutputText(), row.getErrorCode(), errorMessage(row.getErrorCode()),
            row.getProtocol(), row.getModel(), row.getEndpointHost(),
            splitScope(row.getSourceScope()), current == null ? List.of() : current.missing(),
            sourceChanged, row.getCreatedAt(), row.getAdoptedAt(), row.getAdoptedBy(), row.getRequestId());
    }

    private AiGeneration requireRecord(Long orgId, Long activityId, Long generationId) {
        AiGeneration row = generationMapper.findById(generationId);
        if (row == null || !row.getOrgId().equals(orgId)
            || row.getActivityId() == null || !row.getActivityId().equals(activityId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "生成记录不存在或无权访问。");
        }
        return row;
    }

    private String requireType(String type) {
        if (type == null || !AiGeneration.TYPES.contains(type)) {
            throw fieldError("type", "生成类型必须是 ACTIVITY_SUMMARY 或 ACTIVITY_PLAN");
        }
        return type;
    }

    private void requireRequestKey(String requestKey) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 64) {
            throw fieldError("Idempotency-Key", "生成必须携带 1～64 字符的 Idempotency-Key 请求头");
        }
    }

    /** 条件文本：稳定顺序，缺失项写“未填写”，供提示词与请求指纹共用。 */
    private String renderConditions(PlanConditions conditions) {
        PlanConditions value = conditions == null
            ? new PlanConditions(null, null, null, null, null, null, null) : conditions;
        if (isBlank(value.topic()) && isBlank(value.goal())) {
            throw fieldError("topic", "请至少填写活动主题或目标");
        }
        requireLength("topic", value.topic(), 200, "主题");
        requireLength("goal", value.goal(), 500, "目标");
        requireLength("audience", value.audience(), 200, "参与对象");
        requireLength("budget", value.budget(), 200, "预算说明");
        requireLength("timePlace", value.timePlace(), 200, "时间地点条件");
        requireLength("constraints", value.constraints(), 500, "补充限制");
        if (value.expectedParticipants() != null
            && (value.expectedParticipants() < 1 || value.expectedParticipants() > 100000)) {
            throw fieldError("expectedParticipants", "预计人数必须在 1～100000 之间");
        }
        return "主题：" + text(value.topic()) + "\n"
            + "目标：" + text(value.goal()) + "\n"
            + "参与对象：" + text(value.audience()) + "\n"
            + "预计人数：" + (value.expectedParticipants() == null ? "未填写" : value.expectedParticipants()) + "\n"
            + "预算条件：" + text(value.budget()) + "\n"
            + "时间地点条件：" + text(value.timePlace()) + "\n"
            + "补充限制：" + text(value.constraints());
    }

    private void requireLength(String field, String value, int max, String label) {
        if (value != null && value.trim().length() > max) {
            throw fieldError(field, label + "不超过 " + max + " 字符");
        }
    }

    private static String text(String value) {
        return isBlank(value) ? "未填写" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String joinScope(List<String> scope) {
        String joined = String.join("；", scope);
        return joined.length() <= 1000 ? joined : joined.substring(0, 1000);
    }

    private static List<String> splitScope(String scope) {
        return scope == null || scope.isBlank() ? List.of() : List.of(scope.split("；"));
    }

    private static String errorMessage(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case "AI_TIMEOUT" -> "模型调用超时，本次未生成草稿，可继续手工编写或稍后重试";
            case "AI_CONFIG_CHANGED" -> "调用期间模型配置已变化，本次结果未采用";
            case "AI_PERMISSION_REVOKED" -> "调用期间权限或活动状态已变化，本次结果未采用";
            case "AI_UPSTREAM_AUTH" -> "模型服务认证失败，请核对密钥";
            case "AI_UPSTREAM_RATE_LIMITED", "AI_RATE_LIMITED" -> "模型服务限流，请稍后重试";
            case "AI_QUOTA_EXCEEDED" -> "当日调用额度已用完";
            case "AI_INVALID_OUTPUT" -> "模型输出不可用，本次未生成草稿";
            case "AI_OUTPUT_TRUNCATED", "AI_OUTPUT_TOO_LONG" -> "模型输出被截断，本次未生成草稿";
            case "AI_RESPONSE_TOO_LARGE" -> "模型响应过大，本次未生成草稿";
            case "AI_CALL_ABORTED" -> "调用被中断（服务重启或超时），本次未生成草稿";
            default -> "模型调用失败，本次未生成草稿，可继续手工编写";
        };
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效", "请求字段校验失败，请修改后重试。",
            List.of(new com.example.activity.common.FieldErrorItem(field, message)));
    }
}
