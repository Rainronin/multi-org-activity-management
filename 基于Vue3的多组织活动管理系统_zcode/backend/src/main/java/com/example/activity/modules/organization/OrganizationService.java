package com.example.activity.modules.organization;

import com.example.activity.common.ApiException;
import com.example.activity.common.FieldErrorItem;
import com.example.activity.common.RequestFingerprint;
import com.example.activity.common.audit.AuditService;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织业务（P1.2）：创建（创建者自动成为首位负责人）、本人目录、有效成员视角的组织详情。
 * 成员与负责人变更、邀请、移交随 P1.3 落地并引入组织行锁协议。
 */
@Service
public class OrganizationService {

    public record OrganizationCreated(Long orgId, String name, String description, String role, java.time.OffsetDateTime joinedAt) {
    }

    public record OrganizationDirectoryItem(
        Long orgId, String name, String role, java.time.OffsetDateTime joinedAt) {
    }

    public record OrganizationDetail(
        Long orgId, String name, String description, String role, int memberCount, java.time.OffsetDateTime createdAt) {
    }

    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final AuditService auditService;

    public OrganizationService(
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        AuditService auditService) {
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
    }

    @Transactional
    public OrganizationCreated create(Long creatorAccountId, String name, String description, String requestKey) {
        String key = requestKey == null ? null : requestKey.trim();
        if (key != null && (key.isEmpty() || key.length() > 64)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法",
                "Idempotency-Key 必须为 1～64 字符。", List.of(new FieldErrorItem("Idempotency-Key", "长度必须为 1～64 字符")));
        }
        String fingerprint = key == null ? null : RequestFingerprint.of(name, description);
        if (key != null) {
            Organization existing = organizationMapper.findByRequestKey(creatorAccountId, key);
            if (existing != null) {
                organizationMapper.lockOrganizationShared(existing.getId());
                OrganizationMember member = memberMapper.findActive(existing.getId(), creatorAccountId);
                if (member == null || !"ACTIVE".equals(existing.getStatus())) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
                }
                if (!fingerprint.equals(existing.getRequestFingerprint())) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "重复的防重键", "该请求键已用于不同的组织创建内容。");
                }
                return new OrganizationCreated(existing.getId(), existing.getName(), existing.getDescription(), member.getRole(), member.getJoinedAt());
            }
        }
        Organization organization = new Organization();
        organization.setName(name);
        organization.setDescription(description);
        organization.setStatus("ACTIVE");
        organization.setCreatedBy(creatorAccountId);
        organization.setRequestKey(key);
        organization.setRequestFingerprint(fingerprint);
        try {
            organizationMapper.insert(organization);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求已被受理", "请保留原请求键核对创建结果。");
        }

        OrganizationMember member = new OrganizationMember();
        member.setOrgId(organization.getId());
        member.setAccountId(creatorAccountId);
        member.setRole(OrganizationMember.ROLE_OWNER);
        member.setStatus(OrganizationMember.STATUS_ACTIVE);
        memberMapper.insert(member);

        OrganizationMember createdMember = memberMapper.findActive(organization.getId(), creatorAccountId);
        auditService.recordChange(creatorAccountId, "ORGANIZATION", "ORGANIZATION_CREATED",
            "organization:" + organization.getId(), null, java.util.Map.of("name", name), null,
            java.util.Map.of("status", "ACTIVE", "memberId", createdMember.getId(), "role", createdMember.getRole()));
        return new OrganizationCreated(organization.getId(), name, description, createdMember.getRole(), createdMember.getJoinedAt());
    }

    public List<OrganizationDirectoryItem> listMine(Long accountId) {
        return memberMapper.listActiveByAccountId(accountId).stream()
            .map(member -> new OrganizationDirectoryItem(
                member.getOrgId(), organizationName(member.getOrgId()), member.getRole(), member.getJoinedAt()))
            .toList();
    }

    /** 非有效成员与不存在同样返回 404，不泄露组织存在性（开发规范 2.3）。 */
    public OrganizationDetail getDetailForAccount(Long accountId, Long orgId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        Organization organization = member == null ? null : organizationMapper.findById(orgId);
        if (member == null || organization == null || !"ACTIVE".equals(organization.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
        }
        return new OrganizationDetail(
            orgId, organization.getName(), organization.getDescription(), member.getRole(),
            organizationMapper.countActiveMembers(orgId), organization.getCreatedAt());
    }

    private String organizationName(Long orgId) {
        Organization organization = organizationMapper.findById(orgId);
        return organization == null ? "" : organization.getName();
    }
}
