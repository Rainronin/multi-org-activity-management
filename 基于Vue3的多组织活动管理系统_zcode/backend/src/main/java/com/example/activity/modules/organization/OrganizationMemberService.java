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
 *
 * 依赖边界（F04）：活动／任务／物资只做只读职责查询，方向为 organization → 各模块；
 * 退出与移除联动还会取消内部报名、写入站内提示意图，这是跨模块写入，尚未按后端架构三节
 * 提升到 application/MembershipWorkflowService。此处明确保留为最小例外，而不是当作只读依赖：
 * 这些写入始终在组织行排他锁内完成，且活动、任务、物资、报名的写入入口都先取组织锁，
 * 因此仍满足「组织 → 活动 → 子记录」的锁顺序；待职责移交流程接入 P2—P6 时一并提升实现位置。
 */
@Service
public class OrganizationMemberService {

    public static final String CODE_LAST_OWNER = "LAST_OWNER_PROTECTED";

    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final AuditService auditService;
    // 只读职责查询：organization → task/material/activity 单向依赖（后端架构 2.1）
    private final com.example.activity.modules.task.TaskMapper taskMapper;
    private final com.example.activity.modules.material.MaterialMapper materialMapper;
    private final com.example.activity.modules.activity.ActivityMapper activityMapper;
    // 跨模块写入（取消报名、通知意图）：见类注释保留的最小例外
    private final com.example.activity.modules.registration.RegistrationMapper registrationMapper;
    private final com.example.activity.modules.notification.NotificationMapper notificationMapper;

    public OrganizationMemberService(
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        AuditService auditService,
        com.example.activity.modules.task.TaskMapper taskMapper,
        com.example.activity.modules.material.MaterialMapper materialMapper,
        com.example.activity.modules.activity.ActivityMapper activityMapper,
        com.example.activity.modules.registration.RegistrationMapper registrationMapper,
        com.example.activity.modules.notification.NotificationMapper notificationMapper) {
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.auditService = auditService;
        this.taskMapper = taskMapper;
        this.materialMapper = materialMapper;
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
        this.notificationMapper = notificationMapper;
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
        ensureNoOpenDuties(orgId, member.getId());
        // S07-B：退出前按计划书 6.1／4 节处理内部报名，再完成成员状态变更
        cancelInternalRegistrations(orgId, member.getAccountId(), accountId);
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
        ensureNoOpenDuties(orgId, target.getId());
        // S07-B：被移除成员同样处理内部报名，接收人按当次操作固定为被移除账号
        cancelInternalRegistrations(orgId, target.getAccountId(), actorAccountId);
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

    /**
     * 退出/移除前的未结责任检查（P1.4 联动、S07-B）：活动职责、未完成任务、未就绪物资
     * 都要求先安排接手人或合法结束，不允许留下无人负责的事项。
     */
    private void ensureNoOpenDuties(Long orgId, Long memberMemberId) {
        int ownedActivities = activityMapper.countOwnedByMember(orgId, memberMemberId);
        if (ownedActivities > 0) {
            throw conflict("MEMBER_HAS_ACTIVITY_DUTIES", "仍有负责的活动",
                "该成员仍负责 " + ownedActivities + " 个未归档活动，请先变更活动负责人后再操作。");
        }
        int openTasks = taskMapper.countOpenByAssignee(orgId, memberMemberId);
        int openMaterials = materialMapper.countOpenByAssignee(orgId, memberMemberId);
        if (openTasks > 0 || openMaterials > 0) {
            throw conflict("MEMBER_HAS_OPEN_DUTIES", "存在未完成任务或未就绪物资",
                "该成员还有未完成任务或未就绪物资，请先改派或取消后再操作。");
        }
    }

    /**
     * S07-B 退出联动：取消该账号在「尚未进入执行的内部活动」中的待审与有效报名，
     * 保留原因、按主记录原状态释放名额，并给受影响账号留下站内提示意图（计划书 6.1、4 节第 4 条）。
     * 已开始或公开活动的报名保留为历史，不因退出改写。
     */
    private void cancelInternalRegistrations(Long orgId, Long memberAccountId, Long actorAccountId) {
        var affected = registrationMapper.listBlockingForLeave(orgId, memberAccountId);
        for (var registration : affected) {
            String reason = "成员退出组织，内部报名自动取消";
            if (registrationMapper.cancel(registration.getId(), "CANCELLED_ORG", actorAccountId, reason) != 1) {
                // 并发下已被处理：不重复释放名额，也不重复写提示
                continue;
            }
            auditService.record(actorAccountId, "ORGANIZATION", "REGISTRATION_CANCELLED_ON_LEAVE",
                "registration:" + registration.getId(), reason,
                java.util.Map.of("activityId", registration.getActivityId(),
                    "accountId", memberAccountId, "fromStatus", registration.getStatus()));
            notificationMapper.insert(memberAccountId, "REGISTRATION_CANCELLED",
                registration.getActivityId(), "你已退出组织，1 条内部活动报名已取消：" + reason,
                "registration:" + registration.getId() + ":leave");
        }
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
