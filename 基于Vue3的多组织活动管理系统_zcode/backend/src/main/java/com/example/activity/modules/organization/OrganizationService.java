package com.example.activity.modules.organization;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织业务（P1.2）：创建（创建者自动成为首位负责人）、本人目录、有效成员视角的组织详情。
 * 成员与负责人变更、邀请、移交随 P1.3 落地并引入组织行锁协议。
 */
@Service
public class OrganizationService {

    public record OrganizationCreated(Long orgId, String name, String description, String role) {
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
    public OrganizationCreated create(Long creatorAccountId, String name, String description) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setDescription(description);
        organization.setStatus("ACTIVE");
        organizationMapper.insert(organization);

        OrganizationMember member = new OrganizationMember();
        member.setOrgId(organization.getId());
        member.setAccountId(creatorAccountId);
        member.setRole(OrganizationMember.ROLE_OWNER);
        member.setStatus(OrganizationMember.STATUS_ACTIVE);
        memberMapper.insert(member);

        auditService.record(creatorAccountId, "ORGANIZATION", "ORGANIZATION_CREATED",
            "organization:" + organization.getId(), null, java.util.Map.of("name", name));
        return new OrganizationCreated(organization.getId(), name, description, OrganizationMember.ROLE_OWNER);
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
