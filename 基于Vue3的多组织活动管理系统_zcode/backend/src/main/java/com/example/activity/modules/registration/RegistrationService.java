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
        // 活动行排他锁：报名与名额串行化（后端架构 5.2）
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !"APPROVED".equals(activity.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或不在报名阶段。");
        }
        organizationMapper.lockOrganization(activity.getOrgId());

        // 检查报名开关与时间窗
        if (activity.getRegistrationStart() != null && OffsetDateTime.now().isBefore(activity.getRegistrationStart())) {
            throw invalid("报名尚未开始。");
        }
        if (activity.getRegistrationEnd() != null && OffsetDateTime.now().isAfter(activity.getRegistrationEnd())) {
            throw invalid("报名已截止。");
        }

        Registration existing = registrationMapper.findByActivityAndAccount(activityId, accountId);
        if (existing != null) {
            if (Registration.STATUS_PENDING.equals(existing.getStatus())
                || Registration.STATUS_APPROVED.equals(existing.getStatus())) {
                // 幂等：已有待审或有效报名，返回现有记录
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

    /** 审核通过：活动行排他锁内校验名额（并发批准只有一人能获得最后一个名额）。 */
    @Transactional
    public RegistrationItem approve(Long approverAccountId, Long orgId, Long activityId, Long registrationId) {
        organizationMapper.lockOrganization(orgId);
        requireOrgOwner(orgId, approverAccountId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)) {
            throw notFound();
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

    @Transactional
    public RegistrationItem reject(Long approverAccountId, Long orgId, Long activityId, Long registrationId, String reason) {
        organizationMapper.lockOrganization(orgId);
        requireOrgOwner(orgId, approverAccountId);
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

    /** 本人取消：有效报名或待审可取消（释放名额一次）；重复取消幂等。 */
    @Transactional
    public void cancelSelf(Long accountId, Long activityId) {
        organizationMapper.lockOrganization(activityMapper.findById(activityId).getOrgId());
        Registration registration = registrationMapper.findByActivityAndAccount(activityId, accountId);
        if (registration == null) {
            throw notFound();
        }
        if (Registration.STATUS_PENDING.equals(registration.getStatus())) {
            registrationMapper.review(registration.getId(), Registration.STATUS_CANCELLED_SELF, accountId, "本人取消");
        } else if (Registration.STATUS_APPROVED.equals(registration.getStatus())) {
            registrationMapper.review(registration.getId(), Registration.STATUS_CANCELLED_SELF, accountId, "本人取消");
        }
        // 已取消/已驳回的重复取消：幂等不重复变更
        auditService.record(accountId, "REGISTRATION", "REGISTRATION_CANCELLED_SELF",
            "registration:" + registration.getId(), null, java.util.Map.of("activityId", activityId));
    }

    /** 管理者取消：释放名额并记录原因。 */
    @Transactional
    public RegistrationItem cancelOrg(Long actorAccountId, Long orgId, Long activityId, Long registrationId, String reason) {
        organizationMapper.lockOrganization(orgId);
        requireOrgOwner(orgId, actorAccountId);
        Registration registration = requireRegistration(orgId, activityId, registrationId);
        if (!Registration.STATUS_PENDING.equals(registration.getStatus())
            && !Registration.STATUS_APPROVED.equals(registration.getStatus())) {
            throw conflict();
        }
        registrationMapper.review(registrationId, Registration.STATUS_CANCELLED_ORG, actorAccountId, reason);
        auditService.record(actorAccountId, "REGISTRATION", "REGISTRATION_CANCELLED_ORG",
            "registration:" + registrationId, reason, java.util.Map.of("activityId", activityId));
        return toItem(registrationMapper.findById(registrationId), resolveUsername(registration.getAccountId()));
    }

    public RegistrationPage listForReview(
        Long accountId, Long orgId, Long activityId, int page, int pageSize, String status) {
        requireOrgOwner(orgId, accountId);
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

    private void requireOrgOwner(Long orgId, Long accountId) {
        var member = memberMapper.findActive(orgId, accountId);
        if (member == null || !OrganizationMember.ROLE_OWNER.equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以执行此操作。");
        }
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

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "报名不存在或无权访问。");
    }

    private String resolveUsername(Long accountId) {
        return memberMapper.findUsernameByMemberId(null) == null ? "" : accountId.toString();
    }

    private RegistrationItem toItem(Registration r, String username) {
        return new RegistrationItem(
            r.getId(), r.getActivityId(), r.getAccountId(), username,
            r.getDisplayName(), r.getNote(), r.getStatus(), r.getCancelReason(),
            r.getDecidedAt(), r.getCreatedAt());
    }
}
