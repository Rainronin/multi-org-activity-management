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
        // 组织行排他锁 + 锁内重验身份：撤权与创建竞争时，撤权先生效则本次创建被拒绝（R04）。
        organizationMapper.lockOrganization(orgId);
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
        organizationMapper.lockOrganization(orgId);
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
        String codeHash = SecureCodes.hash(SecureCodes.normalize(code));
        OrganizationInvitation lookup = invitationMapper.findActiveByCodeHash(codeHash);
        if (lookup == null) {
            throw invalid();
        }
        // 组织行排他锁：同一组织的邀请接受与成员变更串行（后端架构 5.1）。
        organizationMapper.lockOrganization(lookup.getOrgId());
        // 锁内重读当前状态：READ COMMITTED 下可见竞争事务的已提交结果（R01）。
        OrganizationInvitation invitation = invitationMapper.findById(lookup.getId());
        if (invitation == null || !OrganizationInvitation.STATUS_ACTIVE.equals(invitation.getStatus())) {
            throw invalid();
        }

        OrganizationMember existing = memberMapper.findActive(invitation.getOrgId(), accountId);
        if (existing != null) {
            // 重复接受幂等：返回现有关系，不重复计数、不重复入组。
            return new Accepted(invitation.getOrgId(), orgName(invitation.getOrgId()), existing.getRole());
        }
        OrganizationMember history = memberMapper.findAny(invitation.getOrgId(), accountId);
        if (history != null && "REMOVED".equals(history.getStatus())) {
            // 被移除成员只能凭"移除之后创建"的邀请重新加入；移除前发出的旧邀请对其无效。
            if (!invitation.getCreatedAt().isAfter(history.getUpdatedAt())) {
                throw invalid();
            }
        }
        if (invitationMapper.incrementUsedCount(invitation.getId(), OffsetDateTime.now(), invitation.getMaxUses()) != 1) {
            throw invalid();
        }

        if (history != null && !"ACTIVE".equals(history.getStatus())) {
            // 重新加入复用主关系行（memberId 稳定，见 数据设计.md 组织成员关系）。
            if (memberMapper.reactivate(history.getId()) != 1) {
                throw invalid();
            }
            auditService.record(accountId, "ORGANIZATION", "MEMBER_JOINED", "organization:" + invitation.getOrgId(),
                "重新加入", java.util.Map.of("memberId", history.getId(), "invitationId", invitation.getId()));
            return new Accepted(invitation.getOrgId(), orgName(invitation.getOrgId()), OrganizationMember.ROLE_MEMBER);
        }

        OrganizationMember member = new OrganizationMember();
        member.setOrgId(invitation.getOrgId());
        member.setAccountId(accountId);
        member.setRole(OrganizationMember.ROLE_MEMBER);
        member.setStatus(OrganizationMember.STATUS_ACTIVE);
        try {
            memberMapper.insert(member);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发兜底：锁内 READ COMMITTED 重读可见已提交关系，正常流程不会到达此处。
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
