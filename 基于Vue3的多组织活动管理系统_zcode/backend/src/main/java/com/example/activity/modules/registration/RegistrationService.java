package com.example.activity.modules.registration;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报名业务（P4）：申请、审核（名额在活动行排他锁内校验）、取消与重新申请。
 * 报名窗口与资格校验在锁内执行；重复申请幂等返回现有记录。
 */
@Service
public class RegistrationService {

    private final RegistrationMapper registrationMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMapper organizationMapper;
    private final com.example.activity.modules.organization.OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.account.AccountMapper accountMapper;
    private final AuditService auditService;

    public RegistrationService(
        RegistrationMapper registrationMapper,
        ActivityMapper activityMapper,
        OrganizationMapper organizationMapper,
        com.example.activity.modules.organization.OrganizationMemberMapper memberMapper,
        com.example.activity.modules.account.AccountMapper accountMapper,
        AuditService auditService) {
        this.registrationMapper = registrationMapper;
        this.activityMapper = activityMapper;
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.accountMapper = accountMapper;
        this.auditService = auditService;
    }

    public record RegistrationItem(
        Long registrationId, Long activityId, Long accountId, String accountUsername,
        String displayName, String note, String status, String cancelReason,
        OffsetDateTime decidedAt, OffsetDateTime createdAt) {
    }

    public record RegistrationPage(List<RegistrationItem> items, int page, int pageSize, long total) {
    }

    /** 报名/重新申请：活动须已确认且在有效窗口内；重复申请幂等或更新主记录。 */
    @Transactional
    public RegistrationItem apply(Long accountId, Long activityId, String displayName, String note) {
        Activity activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw notFound();
        }
        // 组织行排他锁：报名与名额串行化（后端架构 5.2）；再取活动行锁后重读状态
        organizationMapper.lockOrganization(activity.getOrgId());
        activityMapper.lockActivity(activityId);
        // S02：锁内重读活动当前状态，防止锁等待期间状态变化
        activity = activityMapper.findById(activityId);
        if (activity == null || !"APPROVED".equals(activity.getStatus())) {
            throw notFound();
        }
        // S02：INTERNAL 活动仅限本组织有效成员报名
        if ("INTERNAL".equals(activity.getRegistrationScope())) {
            OrganizationMember member = memberMapper.findActive(activity.getOrgId(), accountId);
            if (member == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权报名",
                    "该活动仅限本组织成员报名。");
            }
        }
        // S02：报名开关与时间窗只约束新申请（审核既有申请不受影响，后端架构 5.2）
        if (!activity.isRegistrationOpen()) {
            throw invalid("活动报名尚未开放。");
        }
        if (activity.getRegistrationStart() != null && OffsetDateTime.now().isBefore(activity.getRegistrationStart())) {
            throw invalid("报名尚未开始。");
        }
        if (activity.getRegistrationEnd() != null && OffsetDateTime.now().isAfter(activity.getRegistrationEnd())) {
            throw invalid("报名已截止。");
        }
        // 计划书 6.2：到活动开始时间后不再接受新申请
        if (!activity.isBeforeStart()) {
            throw invalid("活动已开始，报名不再开放。");
        }

        Registration existing = registrationMapper.findByActivityAndAccount(activityId, accountId);
        if (existing != null) {
            if (Registration.STATUS_PENDING.equals(existing.getStatus())
                || Registration.STATUS_APPROVED.equals(existing.getStatus())) {
                // 幂等：已有待审或有效报名，返回现有记录而不重复建行
                return toItem(existing, resolveUsername(accountId));
            }
            // 已取消或已驳回：重新申请更新主记录
            registrationMapper.reapply(existing.getId(), displayName, note);
            auditService.record(accountId, "REGISTRATION", "REGISTRATION_REAPPLIED",
                "registration:" + existing.getId(), null, java.util.Map.of("activityId", activityId));
            return toItem(registrationMapper.findByActivityAndAccount(activityId, accountId), resolveUsername(accountId));
        }

        Registration registration = new Registration();
        registration.setOrgId(activity.getOrgId());
        registration.setActivityId(activityId);
        registration.setAccountId(accountId);
        registration.setDisplayName(displayName);
        registration.setNote(note);
        registration.setStatus(Registration.STATUS_PENDING);
        registrationMapper.insert(registration);
        auditService.record(accountId, "REGISTRATION", "REGISTRATION_APPLIED",
            "registration:" + registration.getId(), null, java.util.Map.of("activityId", activityId));
        return toItem(registration, resolveUsername(accountId));
    }

    /**
     * 审核通过：活动须已确认且未到开始时间（S02：开关或窗口关闭不影响审核既有申请，开始后不再批准）。
     * 组织负责人或活动负责人可审核（S04）。
     * S08：组织行锁 → 活动行锁 → 锁内重读活动状态与报名记录，容量校验与状态更新在同一临界区。
     */
    @Transactional
    public RegistrationItem approve(Long approverAccountId, Long orgId, Long activityId, Long registrationId) {
        organizationMapper.lockOrganization(orgId);
        activityMapper.lockActivity(activityId);
        requireReviewPermission(orgId, activityId, approverAccountId);
        Activity activity = requireApprovedActivity(activityId);
        if (!activity.isBeforeStart()) {
            throw conflict("活动已开始，不能再批准报名。");
        }
        Registration registration = requireRegistration(orgId, activityId, registrationId);
        if (!Registration.STATUS_PENDING.equals(registration.getStatus())) {
            throw conflict();
        }
        // 名额校验（锁内：串行化保证最后名额只给一人）
        if (activity.getCapacity() != null
            && registrationMapper.countApproved(activityId) >= activity.getCapacity()) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_FULL", "名额已满",
                "活动名额已用完，无法批准更多报名。");
        }
        if (registrationMapper.review(registrationId, Registration.STATUS_APPROVED, approverAccountId, null) != 1) {
            throw conflict();
        }
        auditService.record(approverAccountId, "REGISTRATION", "REGISTRATION_APPROVED",
            "registration:" + registrationId, null, java.util.Map.of("activityId", activityId));
        return toItem(registrationMapper.findById(registrationId), resolveUsername(registration.getAccountId()));
    }

    /** 驳回待审：开始后仍允许，用于清空待审申请（计划书 6.2 结束审核）。 */
    @Transactional
    public RegistrationItem reject(Long approverAccountId, Long orgId, Long activityId, Long registrationId, String reason) {
        organizationMapper.lockOrganization(orgId);
        activityMapper.lockActivity(activityId);
        requireReviewPermission(orgId, activityId, approverAccountId);
        requireApprovedActivity(activityId);
        Registration registration = requireRegistration(orgId, activityId, registrationId);
        if (!Registration.STATUS_PENDING.equals(registration.getStatus())) {
            throw conflict();
        }
        if (registrationMapper.review(registrationId, Registration.STATUS_REJECTED, approverAccountId, reason) != 1) {
            throw conflict();
        }
        auditService.record(approverAccountId, "REGISTRATION", "REGISTRATION_REJECTED",
            "registration:" + registrationId, reason, null);
        return toItem(registrationMapper.findById(registrationId), resolveUsername(registration.getAccountId()));
    }

    /**
     * 本人取消：活动开始前且未进入执行时，可取消本人待审或已通过报名（计划书 6.2）；
     * 已占名额仅释放一次，重复取消幂等。
     */
    @Transactional
    public void cancelSelf(Long accountId, Long activityId) {
        Activity activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw notFound();
        }
        organizationMapper.lockOrganization(activity.getOrgId());
        // S02／S08：锁内重读，开始时间与状态以当前值为准
        activityMapper.lockActivity(activityId);
        activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw notFound();
        }
        if (!activity.isBeforeStart()) {
            throw conflict("活动已开始，不能再自助取消报名。");
        }
        Registration registration = registrationMapper.findByActivityAndAccount(activityId, accountId);
        if (registration == null) {
            throw notFound();
        }
        boolean cancellable = Registration.STATUS_PENDING.equals(registration.getStatus())
            || Registration.STATUS_APPROVED.equals(registration.getStatus());
        if (!cancellable) {
            // 已取消/已驳回的重复取消：幂等不重复变更
            return;
        }
        // S03：专用取消条件更新（支持 PENDING 和 APPROVED 源状态），检查行数
        if (registrationMapper.cancel(registration.getId(), Registration.STATUS_CANCELLED_SELF, accountId, "本人取消") != 1) {
            return; // 并发下已被处理，幂等返回
        }
        auditService.record(accountId, "REGISTRATION", "REGISTRATION_CANCELLED_SELF",
            "registration:" + registration.getId(), null, java.util.Map.of("activityId", activityId));
    }

    /** 管理取消：组织负责人或活动负责人（S04），活动进入执行前可说明原因取消（计划书 6.2）。 */
    @Transactional
    public RegistrationItem cancelOrg(Long actorAccountId, Long orgId, Long activityId, Long registrationId, String reason) {
        organizationMapper.lockOrganization(orgId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireReviewPermission(orgId, activityId, actorAccountId);
        if (!activity.isBeforeStart()) {
            throw conflict("活动已开始，不能再由管理者取消报名，请在执行记录中说明。");
        }
        Registration registration = requireRegistration(orgId, activityId, registrationId);
        if (!Registration.STATUS_PENDING.equals(registration.getStatus())
            && !Registration.STATUS_APPROVED.equals(registration.getStatus())) {
            throw conflict();
        }
        // S03：专用取消条件更新（支持 PENDING 和 APPROVED 源状态），检查行数
        if (registrationMapper.cancel(registrationId, Registration.STATUS_CANCELLED_ORG, actorAccountId, reason) != 1) {
            throw conflict();
        }
        auditService.record(actorAccountId, "REGISTRATION", "REGISTRATION_CANCELLED_ORG",
            "registration:" + registrationId, reason, java.util.Map.of("activityId", activityId));
        return toItem(registrationMapper.findById(registrationId), resolveUsername(registration.getAccountId()));
    }

    /** 管理名单：组织负责人或本活动负责人（S04）；活动自身可见性同样适用（S06）。 */
    public RegistrationPage listForReview(
        Long accountId, Long orgId, Long activityId, int page, int pageSize, String status) {
        requireReviewPermission(orgId, activityId, accountId);
        String filter = status == null || status.isBlank() ? null : status;
        long total = registrationMapper.countByActivity(activityId, filter);
        List<RegistrationItem> items = registrationMapper
            .listByActivity(activityId, filter, (long) (page - 1) * pageSize, pageSize)
            .stream()
            .map(r -> toItem(r, resolveUsername(r.getAccountId())))
            .toList();
        return new RegistrationPage(items, page, pageSize, total);
    }

    public List<RegistrationItem> listMine(Long accountId) {
        return registrationMapper.listByAccount(accountId).stream()
            .map(r -> toItem(r, resolveUsername(accountId)))
            .toList();
    }

    /**
     * S04：管理权限 = 组织负责人或本活动负责人；同时按活动自身可见性拦截未发布活动（S06）与跨组织读取（S01）。
     * 返回活动当前状态，供调用方在同一锁内继续校验。
     */
    private Activity requireReviewPermission(Long orgId, Long activityId, Long accountId) {
        var member = memberMapper.findActive(orgId, accountId);
        if (member == null) {
            throw notFound();
        }
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !activity.isVisibleTo(member, accountId)) {
            throw notFound();
        }
        boolean isOrgOwner = OrganizationMember.ROLE_OWNER.equals(member.getRole());
        boolean isActivityOwner = member.getId().equals(activity.getOwnerMemberId());
        if (!isOrgOwner && !isActivityOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问",
                "仅组织负责人或活动负责人可以管理报名。");
        }
        return activity;
    }

    /** 审核／管理取消的公共前置：活动须存在且为已确认（撤回草稿、待确认不可处理）。 */
    private Activity requireApprovedActivity(Long activityId) {
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !Activity.STATUS_APPROVED.equals(activity.getStatus())) {
            throw conflict("活动未处于已确认状态，无法处理报名。");
        }
        return activity;
    }

    private Registration requireRegistration(Long orgId, Long activityId, Long registrationId) {
        Registration registration = registrationMapper.findById(registrationId);
        if (registration == null || !registration.getOrgId().equals(orgId)
            || !registration.getActivityId().equals(activityId)) {
            throw notFound();
        }
        return registration;
    }

    private ApiException invalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "REGISTRATION_INVALID", "报名无效", detail);
    }

    private ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "REGISTRATION_STATE_CONFLICT", "报名状态不允许此操作",
            "报名已被处理，请刷新后查看。");
    }

    private ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "REGISTRATION_STATE_CONFLICT", "报名状态不允许此操作", detail);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "报名不存在或无权访问。");
    }

    private String resolveUsername(Long accountId) {
        String username = accountMapper.findUsernameById(accountId);
        return username == null ? "" : username;
    }

    private RegistrationItem toItem(Registration r, String username) {
        return new RegistrationItem(
            r.getId(), r.getActivityId(), r.getAccountId(), username,
            r.getDisplayName(), r.getNote(), r.getStatus(), r.getCancelReason(),
            r.getDecidedAt(), r.getCreatedAt());
    }
}
