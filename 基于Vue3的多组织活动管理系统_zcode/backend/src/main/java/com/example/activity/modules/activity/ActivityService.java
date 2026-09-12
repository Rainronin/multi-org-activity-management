package com.example.activity.modules.activity;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动业务（P2.1：草稿创建、列表、详情与编辑）。
 * 写操作取组织行共享锁校验有效成员；草稿仅组织负责人与被指定的活动负责人可见；
 * 时间顺序、预算精度与名额校验在服务端统一执行（接口说明 P2.1）。
 */
@Service
public class ActivityService {

    public record ActivityItem(
        Long activityId, String name, String ownerMemberId, String ownerUsername, String status,
        OffsetDateTime startTime, OffsetDateTime endTime, String location, String budget,
        String registrationScope, Integer capacity, OffsetDateTime registrationStart,
        OffsetDateTime registrationEnd, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ActivityPage(java.util.List<ActivityItem> items, int page, int pageSize, long total) {
    }

    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.organization.OrganizationMapper organizationMapper;
    private final AuditService auditService;

    public ActivityService(
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.organization.OrganizationMapper organizationMapper,
        AuditService auditService) {
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.organizationMapper = organizationMapper;
        this.auditService = auditService;
    }

    /** 创建草稿：仅组织负责人；负责人须为本组织有效成员（可为创建者本人）。 */
    @Transactional
    public ActivityItem create(
        Long creatorAccountId, String creatorUsername, Long orgId, Activity draft) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, creatorAccountId);
        if (!OrganizationMember.ROLE_OWNER.equals(actor.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以创建活动。");
        }
        validateOwnerMember(orgId, draft.getOwnerMemberId());
        validateTimes(draft);
        draft.setOrgId(orgId);
        draft.setStatus(Activity.STATUS_DRAFT);
        draft.setCreatedBy(creatorAccountId);
        if (draft.getBudget() == null) {
            draft.setBudget(new BigDecimal("0.00"));
        }
        if (draft.getRegistrationScope() == null) {
            draft.setRegistrationScope(Activity.SCOPE_INTERNAL);
        }
        activityMapper.insert(draft);
        auditService.record(creatorAccountId, "ACTIVITY", "ACTIVITY_DRAFT_CREATED",
            "activity:" + draft.getId(), null, java.util.Map.of("name", draft.getName()));
        return toItem(activityMapper.findById(draft.getId()));
    }

    /** 列表：组织负责人见全部草稿；普通成员当前返回空集（发布可见性随 P2.2 扩展）。 */
    public ActivityPage list(Long accountId, Long orgId, int page, int pageSize, String query, String status) {
        requireActiveMember(orgId, accountId);
        String effectiveStatus = status == null || status.isBlank() ? Activity.STATUS_DRAFT : status;
        long total;
        List<ActivityItem> items;
        if (isOwner(orgId, accountId)) {
            total = activityMapper.countPage(orgId, blankToNull(query), effectiveStatus);
            items = activityMapper
                .listPage(orgId, blankToNull(query), effectiveStatus, (long) (page - 1) * pageSize, pageSize)
                .stream()
                .map(this::toItem)
                .toList();
        } else {
            total = 0;
            items = List.of();
        }
        return new ActivityPage(items, page, pageSize, total);
    }

    /** 详情：草稿仅组织负责人与活动负责人可见；其余与不存在同响应。 */
    public ActivityItem get(Long accountId, Long orgId, Long activityId) {
        requireActiveMember(orgId, accountId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId) || !canSeeDraft(activity, accountId)) {
            throw notFound();
        }
        return toItem(activity);
    }

    /** 编辑草稿：组织负责人或活动负责人；PATCH 仅更新显式字段。 */
    @Transactional
    public ActivityItem updateDraft(Long accountId, Long orgId, Long activityId, Activity patch) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !canSeeDraft(activity, accountId)) {
            throw notFound();
        }
        boolean isOrgOwner = OrganizationMember.ROLE_OWNER.equals(actor.getRole());
        boolean isActivityOwner = actor.getId().equals(activity.getOwnerMemberId());
        if (!isOrgOwner && !isActivityOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以编辑草稿。");
        }
        if (patch.getOwnerMemberId() != null) {
            if (!isOrgOwner) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以变更活动负责人。");
            }
            validateOwnerMember(orgId, patch.getOwnerMemberId());
        }
        // 合并后校验时间顺序：以编辑后的整体状态判断
        Activity merged = mergeForValidation(activity, patch);
        validateTimes(merged);
        if (patch.getBudget() != null && patch.getBudget().signum() < 0) {
            throw fieldError("budget", "预算不能为负数");
        }
        if (patch.getCapacity() != null && patch.getCapacity() <= 0) {
            throw fieldError("capacity", "名额必须为正整数");
        }
        if (activityMapper.updateDraft(patch) != 1) {
            throw notFound();
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_DRAFT_UPDATED", "activity:" + activityId,
            null, java.util.Map.of("fields", changedFields(patch)));
        return toItem(activityMapper.findById(activityId));
    }

    private java.util.List<String> changedFields(Activity patch) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        if (patch.getName() != null) fields.add("name");
        if (patch.getDescription() != null) fields.add("description");
        if (patch.getOwnerMemberId() != null) fields.add("ownerMemberId");
        if (patch.getStartTime() != null) fields.add("startTime");
        if (patch.getEndTime() != null) fields.add("endTime");
        if (patch.getLocation() != null) fields.add("location");
        if (patch.getBudget() != null) fields.add("budget");
        if (patch.getRegistrationScope() != null) fields.add("registrationScope");
        if (patch.getCapacity() != null) fields.add("capacity");
        if (patch.getRegistrationStart() != null) fields.add("registrationStart");
        if (patch.getRegistrationEnd() != null) fields.add("registrationEnd");
        return fields;
    }

    private Activity mergeForValidation(Activity current, Activity patch) {
        Activity merged = new Activity();
        merged.setStartTime(patch.getStartTime() != null ? patch.getStartTime() : current.getStartTime());
        merged.setEndTime(patch.getEndTime() != null ? patch.getEndTime() : current.getEndTime());
        merged.setRegistrationStart(patch.getRegistrationStart() != null
            ? patch.getRegistrationStart() : current.getRegistrationStart());
        merged.setRegistrationEnd(patch.getRegistrationEnd() != null
            ? patch.getRegistrationEnd() : current.getRegistrationEnd());
        return merged;
    }

    private void validateTimes(Activity draft) {
        if (draft.getStartTime() != null && draft.getEndTime() != null
            && !draft.getEndTime().isAfter(draft.getStartTime())) {
            throw fieldError("endTime", "活动结束时间必须晚于开始时间");
        }
        if (draft.getRegistrationStart() != null && draft.getRegistrationEnd() != null
            && !draft.getRegistrationEnd().isAfter(draft.getRegistrationStart())) {
            throw fieldError("registrationEnd", "报名截止必须晚于报名开始");
        }
        if (draft.getRegistrationEnd() != null && draft.getStartTime() != null
            && draft.getRegistrationEnd().isAfter(draft.getStartTime())) {
            throw fieldError("registrationEnd", "报名截止不能晚于活动开始");
        }
    }

    private void validateOwnerMember(Long orgId, Long ownerMemberId) {
        OrganizationMember owner = memberMapper.findById(ownerMemberId);
        if (owner == null || !owner.getOrgId().equals(orgId)
            || !OrganizationMember.STATUS_ACTIVE.equals(owner.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACTIVITY_OWNER_INVALID", "活动负责人无效",
                "活动负责人必须是本组织的有效成员。",
                java.util.List.of(new com.example.activity.common.FieldErrorItem(
                    "ownerMemberId", "必须是本组织的有效成员")));
        }
    }

    private OrganizationMember requireActiveMember(Long orgId, Long accountId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        if (member == null) {
            throw notFound();
        }
        return member;
    }

    private boolean isOwner(Long orgId, Long accountId) {
        OrganizationMember member = memberMapper.findActive(orgId, accountId);
        return member != null && OrganizationMember.ROLE_OWNER.equals(member.getRole());
    }

    private boolean canSeeDraft(Activity activity, Long accountId) {
        OrganizationMember member = memberMapper.findActive(activity.getOrgId(), accountId);
        if (member == null) {
            return false;
        }
        return OrganizationMember.ROLE_OWNER.equals(member.getRole())
            || member.getId().equals(activity.getOwnerMemberId());
    }

    private ActivityItem toItem(Activity activity) {
        return new ActivityItem(
            activity.getId(), activity.getName(), String.valueOf(activity.getOwnerMemberId()),
            memberMapper.findUsernameByMemberId(activity.getOwnerMemberId()), activity.getStatus(),
            activity.getStartTime(), activity.getEndTime(),
            activity.getLocation(), activity.getBudget() == null
                ? "0.00" : activity.getBudget().setScale(2).toPlainString(),
            activity.getRegistrationScope(), activity.getCapacity(),
            activity.getRegistrationStart(), activity.getRegistrationEnd(),
            activity.getCreatedAt(), activity.getUpdatedAt());
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效", "请求字段校验失败，请修改后重试。",
            List.of(new com.example.activity.common.FieldErrorItem(field, message)));
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
