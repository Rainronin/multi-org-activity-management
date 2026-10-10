package com.example.activity.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 关键业务变更审计：与业务写入同一事务调用（调用方 @Transactional），
 * 写入失败随业务回滚；detail 仅存非敏感结构化信息，禁止凭据与恢复码明文。
 */
@Service
public class AuditService {

    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public AuditService(AuditMapper auditMapper, ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    public void record(Long actorAccountId, String scope, String action, String target, String reason, Object detail) {
        try {
            String detailJson = detail == null ? null : objectMapper.writeValueAsString(detail);
            auditMapper.insert(actorAccountId, scope, action, target, reason, detailJson, MDC.get("requestId"));
        } catch (JsonProcessingException ex) {
            // 审计是业务提交条件的一部分；序列化异常按未预期异常处理，随事务回滚。
            throw new IllegalStateException("审计明细序列化失败", ex);
        }
    }

    /** 调用方传入白名单状态，不序列化包含密码、密钥或正文的业务实体。 */
    public void recordChange(Long actorAccountId, String scope, String action, String target, String reason,
        java.util.Map<String, ?> detail, Object before, Object after) {
        java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
        if (detail != null) entry.putAll(detail);
        entry.put("before", before);
        entry.put("after", after);
        record(actorAccountId, scope, action, target, reason, entry);
    }
}
