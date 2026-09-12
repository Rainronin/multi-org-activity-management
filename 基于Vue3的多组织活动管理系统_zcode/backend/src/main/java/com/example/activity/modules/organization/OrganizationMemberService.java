package com.example.activity.modules.organization;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织成员与负责人管理（P1.3）。所有变更在组织行排他锁内校验并提交（后端架构 5.1）；
 * 最后一名负责人受保护；历史关系置 LEFT／REMOVED 保留，不物理删除。
 * 跨业务职责移交（活动、任务、物资、报名）随 P2—P6 接入时提升为 application 编排。
 */
@Service
public class OrganizationMemberService {

    public static final String CODE_LAST_OWNER = "LAST_OWNER_PROTECTED";

    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final AuditService auditService;

    public OrganizationMemberService(
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        AuditService auditService) {
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
    }

    public record MemberItem(Long memberId, Long accountId, String username, String role, java.time.OffsetDateTime joinedAt) {
    }

    public record MemberPage(java.util.List<MemberItem> items, int page, int pageSize, long total) {
    }

    public record RoleChangeResult(Long memberId, String role) {
    }

    /** 成员列表：小规模组织按分页合同返回；query 按用户名过滤，排序固定为加入时间。 */
    public MemberPage listMembers(Long accountId, Long orgId, int page, int pageSize, String query) {
        requireActiveMember(orgId, accountId);
        String filter = query == null || query.isBlank() ? null : query.trim();
        long total = memberMapper.countMemberViews(orgId, filter);
        List<MemberItem> items = memberMapper
            .listMemberViewsPage(orgId, filter, (long) (page - 1) * pageSize, pageSize)
            .stream()
            .map(view -> new MemberItem(view.getMemberId(), view.getAccountId(), view.getUsername(), view.getRole(), view.getJoinedAt()))
            .toList();
        return new MemberPage(items, page, pageSize, total);
    }

    @Transactional
    public void leave(Long accountId, Long orgId) {
        organizationMapper.lockOrganization(orgId);
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        if (member == null) {
            // 从未加入过与已退出统一 404；重复退出由"已有历史关系"分支幂等处理。
            OrganizationMember any = memberMapper.findAny(orgId, accountId);
            if (any == null) {
                throw notFound();
            }
            return;
        }
        ensureNotLastOwner(orgId, member.getId(), member.getRole());
        if (memberMapper.updateStatus(member.getId(), "LEFT") != 1) {
            throw conflict("MEMBER_STATE_CONFLICT", "成员状态已变化", "成员关系已被处理，请刷新后查看。");
        }
        auditService.record(accountId, "ORGANIZATION", "MEMBER_LEFT", "organization:" + orgId, null,
            java.util.Map.of("memberId", member.getId()));
    }

    @Transactional
    public void remove(Long actorAccountId, Long orgId, Long memberId) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, actorAccountId);
        OrganizationMember target = memberMapper.findById(memberId);
        if (target == null || !target.getOrgId().equals(orgId)) {
            throw notFound();
        }
        if (target.getAccountId().equals(actorAccountId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEMBER_SELF_REMOVAL", "不能移除自己",
                "如需离开组织，请使用退出组织。");
        }
        if (!"ACTIVE".equals(target.getStatus())) {
            // 已退出／已移除：对已授权操作者幂等返回，不重复变更、不泄露历史细节。
            return;
        }
        ensureNotLastOwner(orgId, target.getId(), target.getRole());
        if (memberMapper.updateStatus(target.getId(), "REMOVED") != 1) {
            throw conflict("MEMBER_STATE_CONFLICT", "成员状态已变化", "成员关系已被处理，请刷新后查看。");
        }
        auditService.record(actorAccountId, "ORGANIZATION", "MEMBER_REMOVED", "organization:" + orgId,
            null, java.util.Map.of("memberId", target.getId(), "accountId", target.getAccountId()));
    }

    @Transactional
    public RoleChangeResult promoteOwner(Long actorAccountId, Long orgId, Long memberId) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, actorAccountId);
        OrganizationMember target = requireActiveTarget(orgId, memberId);
        if (!OrganizationMember.ROLE_MEMBER.equals(target.getRole())) {
            throw conflict("MEMBER_ALREADY_OWNER", "该成员已是负责人", "无需重复授予负责人。");
        }
        if (memberMapper.updateRole(target.getId(), OrganizationMember.ROLE_OWNER) != 1) {
            throw conflict("MEMBER_STATE_CONFLICT", "成员状态已变化", "成员关系已被处理，请刷新后查看。");
        }
        auditService.record(actorAccountId, "ORGANIZATION", "OWNER_PROMOTED", "organization:" + orgId,
            null, java.util.Map.of("memberId", target.getId(), "accountId", target.getAccountId()));
        return new RoleChangeResult(target.getId(), OrganizationMember.ROLE_OWNER);
    }

    @Transactional
    public RoleChangeResult revokeOwner(Long actorAccountId, Long orgId, Long memberId) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, actorAccountId);
        OrganizationMember target = requireActiveTarget(orgId, memberId);
        if (!OrganizationMember.ROLE_OWNER.equals(target.getRole())) {
            throw conflict("MEMBER_NOT_OWNER", "该成员不是负责人", "只有负责人可以被撤销负责人身份。");
        }
        ensureNotLastOwner(orgId, target.getId(), target.getRole());
        if (memberMapper.updateRole(target.getId(), OrganizationMember.ROLE_MEMBER) != 1) {
            throw conflict("MEMBER_STATE_CONFLICT", "成员状态已变化", "成员关系已被处理，请刷新后查看。");
        }
        auditService.record(actorAccountId, "ORGANIZATION", "OWNER_REVOKED", "organization:" + orgId,
            null, java.util.Map.of("memberId", target.getId(), "accountId", target.getAccountId()));
        return new RoleChangeResult(target.getId(), OrganizationMember.ROLE_MEMBER);
    }

    /** 移交：同一事务内授予目标并自降为普通成员，组织始终保留至少一名负责人。 */
    @Transactional
    public RoleChangeResult transferOwnership(Long actorAccountId, Long orgId, Long toMemberId) {
        organizationMapper.lockOrganization(orgId);
        OrganizationMember actor = requireOwner(orgId, actorAccountId);
        if (toMemberId.equals(actor.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRANSFER_TARGET_INVALID", "移交对象无效",
                "不能把负责人移交给自己的当前身份。");
        }
        OrganizationMember target = requireActiveTarget(orgId, toMemberId);
        if (!OrganizationMember.ROLE_MEMBER.equals(target.getRole())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRANSFER_TARGET_INVALID", "移交对象无效",
                "移交对象需要是组织的普通成员。");
        }
        if (memberMapper.updateRole(target.getId(), OrganizationMember.ROLE_OWNER) != 1
            || memberMapper.updateRole(actor.getId(), OrganizationMember.ROLE_MEMBER) != 1) {
            throw conflict("MEMBER_STATE_CONFLICT", "成员状态已变化", "成员关系已被处理，请刷新后查看。");
        }
        auditService.record(actorAccountId, "ORGANIZATION", "OWNERSHIP_TRANSFERRED", "organization:" + orgId,
            null, java.util.Map.of("fromMemberId", actor.getId(), "toMemberId", target.getId()));
        return new RoleChangeResult(actor.getId(), OrganizationMember.ROLE_MEMBER);
    }

    private OrganizationMember requireActiveMember(Long orgId, Long accountId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        if (member == null) {
            throw notFound();
        }
        return member;
    }

    private OrganizationMember requireOwner(Long orgId, Long accountId) {
        OrganizationMember member = requireActiveMember(orgId, accountId);
        if (!OrganizationMember.ROLE_OWNER.equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以执行此操作。");
        }
        return member;
    }

    private OrganizationMember requireActiveTarget(Long orgId, Long memberId) {
        OrganizationMember target = memberMapper.findById(memberId);
        if (target == null || !target.getOrgId().equals(orgId) || !"ACTIVE".equals(target.getStatus())) {
            throw notFound();
        }
        return target;
    }

    private void ensureNotLastOwner(Long orgId, Long memberId, String role) {
        if (!OrganizationMember.ROLE_OWNER.equals(role)) {
            return;
        }
        if (memberMapper.countActiveOwners(orgId) <= 1) {
            throw conflict(CODE_LAST_OWNER, "不能移除最后一名负责人",
                "组织至少保留一名负责人，请先把负责人移交给其他成员。");
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "组织不存在或无权访问。");
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }
}
