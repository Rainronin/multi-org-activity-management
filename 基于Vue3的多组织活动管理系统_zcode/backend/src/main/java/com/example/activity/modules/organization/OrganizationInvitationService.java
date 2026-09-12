package com.example.activity.modules.organization;

import com.example.activity.common.ApiException;
import com.example.activity.common.SecureCodes;
import com.example.activity.common.audit.AuditService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织邀请（P1.3）：邀请只授予普通成员身份；有效性（未撤销、未过期、未满额）
 * 在组织行排他锁内以条件更新扣减，重复接受幂等返回现有关系，不重复入组。
 */
@Service
public class OrganizationInvitationService {

    public static final String CODE_INVITATION_INVALID = "INVITATION_INVALID";

    public record InvitationCreated(Long invitationId, String code, OffsetDateTime expiresAt, int maxUses) {
    }

    public record InvitationItem(
        Long invitationId, Long createdBy, OffsetDateTime createdAt, OffsetDateTime expiresAt,
        int maxUses, int usedCount, String status) {
    }

    public record Accepted(Long orgId, String name, String role) {
    }

    private final OrganizationInvitationMapper invitationMapper;
    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final AuditService auditService;

    public OrganizationInvitationService(
        OrganizationInvitationMapper invitationMapper,
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        AuditService auditService) {
        this.invitationMapper = invitationMapper;
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
    }

    @Transactional
    public InvitationCreated create(Long actorAccountId, Long orgId, int expiresInDays, int maxUses) {
        requireOwner(orgId, actorAccountId);
        // 邀请码需要分享给被邀请人，明文仅创建响应返回一次（存哈希，之后不可再查看）。
        String plainCode = SecureCodes.generate();
        OrganizationInvitation invitation = new OrganizationInvitation();
        invitation.setOrgId(orgId);
        invitation.setCodeHash(SecureCodes.hash(SecureCodes.normalize(plainCode)));
        invitation.setCreatedBy(actorAccountId);
        invitation.setExpiresAt(OffsetDateTime.now().plusDays(expiresInDays));
        invitation.setMaxUses(maxUses);
        invitation.setStatus(OrganizationInvitation.STATUS_ACTIVE);
        invitationMapper.insert(invitation);
        auditService.record(actorAccountId, "ORGANIZATION", "INVITATION_CREATED", "organization:" + orgId,
            null, java.util.Map.of("invitationId", invitation.getId(), "maxUses", maxUses, "expiresInDays", expiresInDays));
        return new InvitationCreated(invitation.getId(), plainCode, invitation.getExpiresAt(), maxUses);
    }

    public List<InvitationItem> list(Long actorAccountId, Long orgId) {
        requireOwner(orgId, actorAccountId);
        return invitationMapper.listActiveByOrgId(orgId).stream()
            .map(item -> new InvitationItem(item.getId(), item.getCreatedBy(), item.getCreatedAt(),
                item.getExpiresAt(), item.getMaxUses(), item.getUsedCount(), item.getStatus()))
            .toList();
    }

    @Transactional
    public void revoke(Long actorAccountId, Long orgId, Long invitationId) {
        requireOwner(orgId, actorAccountId);
        OrganizationInvitation invitation = invitationMapper.findById(invitationId);
        if (invitation == null || !invitation.getOrgId().equals(orgId)) {
            throw notFound();
        }
        invitationMapper.revoke(invitationId);
        auditService.record(actorAccountId, "ORGANIZATION", "INVITATION_REVOKED", "organization:" + orgId,
            null, java.util.Map.of("invitationId", invitationId));
    }

    @Transactional
    public Accepted accept(Long accountId, String code) {
        OrganizationInvitation invitation =
            invitationMapper.findActiveByCodeHash(SecureCodes.hash(SecureCodes.normalize(code)));
        if (invitation == null) {
            throw invalid();
        }
        // 组织行排他锁：同一组织的邀请接受与成员变更串行。
        organizationMapper.lockOrganization(invitation.getOrgId());

        OrganizationMember existing = memberMapper.findActive(invitation.getOrgId(), accountId);
        if (existing != null) {
            // 重复接受幂等：返回现有关系，不重复计数、不重复入组。
            return new Accepted(invitation.getOrgId(), orgName(invitation.getOrgId()), existing.getRole());
        }
        if (invitationMapper.incrementUsedCount(invitation.getId(), OffsetDateTime.now(), invitation.getMaxUses()) != 1) {
            throw invalid();
        }
        OrganizationMember member = new OrganizationMember();
        member.setOrgId(invitation.getOrgId());
        member.setAccountId(accountId);
        member.setRole(OrganizationMember.ROLE_MEMBER);
        member.setStatus(OrganizationMember.STATUS_ACTIVE);
        try {
            memberMapper.insert(member);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发兜底：唯一约束保证不产生第二条有效关系。
            OrganizationMember concurrent = memberMapper.findActive(invitation.getOrgId(), accountId);
            if (concurrent == null) {
                throw ex;
            }
            return new Accepted(invitation.getOrgId(), orgName(invitation.getOrgId()), concurrent.getRole());
        }
        auditService.record(accountId, "ORGANIZATION", "MEMBER_JOINED", "organization:" + invitation.getOrgId(),
            null, java.util.Map.of("memberId", member.getId(), "invitationId", invitation.getId()));
        return new Accepted(invitation.getOrgId(), orgName(invitation.getOrgId()), member.getRole());
    }

    private void requireOwner(Long orgId, Long accountId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        if (member == null) {
            throw notFound();
        }
        if (!OrganizationMember.ROLE_OWNER.equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以执行此操作。");
        }
    }

    private String orgName(Long orgId) {
        Organization organization = organizationMapper.findById(orgId);
        return organization == null ? "" : organization.getName();
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, CODE_INVITATION_INVALID, "邀请无效",
            "邀请码无效、已过期或已被用完，请向组织负责人确认。");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
    }
}
