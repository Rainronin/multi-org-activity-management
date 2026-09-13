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
 * 写操作取组织行共享锁校验有效成员；未发布（草稿／待确认）活动仅组织负责人与被指定的活动负责人可见，
 * 列表、详情及任务／物资／附件子资源使用同一可见性（S06）；报名开关独立于申请时间窗（S02）；
 * 时间顺序、预算精度与名额校验在服务端统一执行（接口说明 P2.1）。
 */
@Service
public class ActivityService {

    public record ActivityItem(
        Long activityId, String name, String description, String ownerMemberId, String ownerUsername,
        String status, String proposal, String reviewNote, Long submittedBy, Long confirmedBy, String confirmType,
        OffsetDateTime confirmedAt, OffsetDateTime startTime, OffsetDateTime endTime, String location, String budget,
        String registrationScope, Integer capacity, OffsetDateTime registrationStart,
        OffsetDateTime registrationEnd, boolean registrationOpen, Long version,
        // P6：执行、取消、归档与总结
        OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime archivedAt, OffsetDateTime cancelledAt,
        String cancelReason, String reopenReason, Integer reopenCount,
        String summary, OffsetDateTime summaryConfirmedAt, Long summaryConfirmedBy,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ActivityPage(java.util.List<ActivityItem> items, int page, int pageSize, long total) {
    }

    private final ActivityMapper activityMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.organization.OrganizationMapper organizationMapper;
    private final AuditService auditService;
    // 只读依赖查询：activity → task/material/attachment/registration 单向依赖（后端架构 2.1）
    private final com.example.activity.modules.task.TaskMapper taskMapper;
    private final com.example.activity.modules.material.MaterialMapper materialMapper;
    private final com.example.activity.modules.attachment.AttachmentMapper attachmentMapper;
    private final com.example.activity.modules.registration.RegistrationMapper registrationMapper;
    /** P5：草稿删除前清点费用登记（跨模块只读依赖）。 */
    private final com.example.activity.modules.expense.ExpenseMapper expenseMapper;
    // F08：关键活动变更的站内提示来源意图（计划书 6.7），与业务同事务落库
    private final com.example.activity.modules.notification.NotificationMapper notificationMapper;
    // P6：执行记录与反馈资格依据（总结来源摘要需要反馈口径）
    private final ExecutionRecordMapper executionRecordMapper;
    private final com.example.activity.modules.feedback.FeedbackService feedbackService;
    private final com.example.activity.modules.account.AccountMapper accountMapper;

    public ActivityService(
        ActivityMapper activityMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.organization.OrganizationMapper organizationMapper,
        AuditService auditService,
        com.example.activity.modules.task.TaskMapper taskMapper,
        com.example.activity.modules.material.MaterialMapper materialMapper,
        com.example.activity.modules.attachment.AttachmentMapper attachmentMapper,
        com.example.activity.modules.registration.RegistrationMapper registrationMapper,
        com.example.activity.modules.expense.ExpenseMapper expenseMapper,
        com.example.activity.modules.notification.NotificationMapper notificationMapper,
        ExecutionRecordMapper executionRecordMapper,
        com.example.activity.modules.feedback.FeedbackService feedbackService,
        com.example.activity.modules.account.AccountMapper accountMapper) {
        this.activityMapper = activityMapper;
        this.memberMapper = memberMapper;
        this.organizationMapper = organizationMapper;
        this.auditService = auditService;
        this.taskMapper = taskMapper;
        this.materialMapper = materialMapper;
        this.attachmentMapper = attachmentMapper;
        this.registrationMapper = registrationMapper;
        this.expenseMapper = expenseMapper;
        this.notificationMapper = notificationMapper;
        this.executionRecordMapper = executionRecordMapper;
        this.feedbackService = feedbackService;
        this.accountMapper = accountMapper;
    }

    /**
     * 创建草稿：仅组织负责人；负责人须为本组织有效成员（可为创建者本人）。
     * S08：必须携带 Idempotency-Key；同组织同键重放返回已提交资源，不重复建草稿。
     * 先判权限再判防重键，避免未授权调用者借错误码探测接口约定。
     */
    @Transactional
    public ActivityItem create(
        Long creatorAccountId, String creatorUsername, Long orgId, String idempotencyKey, Activity draft) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, creatorAccountId);
        if (!OrganizationMember.ROLE_OWNER.equals(actor.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以创建活动。");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "缺少防重键",
                "创建活动必须携带 Idempotency-Key 请求头（1～64 字符）。");
        }
        // 与落库同规则地补齐默认值，保证重放比较与首次创建看到同一份内容
        applyCreationDefaults(draft);
        String fingerprint = creationFingerprint(draft);
        // 防重：先查已提交结果（重放仍校验当前身份与可见性，不返回撤权前的私有结果）
        Activity existing = activityMapper.findByRequestKey(orgId, idempotencyKey);
        if (existing != null) {
            if (!existing.isVisibleTo(actor, creatorAccountId)) {
                throw notFound();
            }
            return replayResult(existing, fingerprint);
        }
        validateOwnerMember(orgId, draft.getOwnerMemberId());
        validateTimes(draft);
        draft.setOrgId(orgId);
        draft.setStatus(Activity.STATUS_DRAFT);
        draft.setCreatedBy(creatorAccountId);
        draft.setRequestKey(idempotencyKey);
        draft.setRequestFingerprint(fingerprint);
        try {
            activityMapper.insert(draft);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // F06：同键并发创建由唯一约束兜底；冲突后按已提交结果恢复，不返回 500
            Activity committed = activityMapper.findByRequestKey(orgId, idempotencyKey);
            if (committed == null) {
                throw duplicate;
            }
            return replayResult(committed, fingerprint);
        }
        auditService.record(creatorAccountId, "ACTIVITY", "ACTIVITY_DRAFT_CREATED",
            "activity:" + draft.getId(), null, java.util.Map.of("name", draft.getName()));
        return toItem(activityMapper.findById(draft.getId()));
    }

    /** 创建与重放比较共用的默认值补齐：预算为零、范围默认内部。 */
    private void applyCreationDefaults(Activity draft) {
        if (draft.getBudget() == null) {
            draft.setBudget(new BigDecimal("0.00"));
        }
        if (draft.getRegistrationScope() == null) {
            draft.setRegistrationScope(Activity.SCOPE_INTERNAL);
        }
    }

    /** F06：同键重放的结果核对——只比较创建时写下的指纹，不比较可能已被编辑的当前字段。 */
    private ActivityItem replayResult(Activity existing, String fingerprint) {
        // 指纹是 V14 引入的列：迁移前创建的记录没有指纹，无法核对内容，按“键已占用”处理
        boolean same = fingerprint.equals(existing.getRequestFingerprint());
        if (!same) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "重复的防重键",
                "该 Idempotency-Key 已用于创建内容不同的活动，请使用新的键重试。");
        }
        return toItem(existing);
    }

    /**
     * F06：创建请求指纹——对规范化后的业务字段求 SHA-256。
     * 只覆盖创建输入本身，创建后的编辑（改名、改地点、改状态）都不影响它，重放因此不会被误判。
     */
    private String creationFingerprint(Activity draft) {
        String canonical = String.join("\u001f",
            String.valueOf(draft.getName()),
            String.valueOf(draft.getOwnerMemberId()),
            String.valueOf(draft.getDescription()),
            String.valueOf(draft.getStartTime()),
            String.valueOf(draft.getEndTime()),
            String.valueOf(draft.getLocation()),
            String.valueOf(draft.getRegistrationScope()),
            String.valueOf(draft.getCapacity()),
            String.valueOf(draft.getRegistrationStart()),
            String.valueOf(draft.getRegistrationEnd()),
            draft.getBudget() == null ? "null" : draft.getBudget().stripTrailingZeros().toPlainString());
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 列表：可见性与详情一致（S06）——组织负责人见全部状态；其他成员见已确认活动，
     * 以及本人负责的草稿／待确认活动；筛选状态与分页总数使用同一条件。
     */
    public ActivityPage list(Long accountId, Long orgId, int page, int pageSize, String query, String status) {
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        boolean orgOwner = OrganizationMember.ROLE_OWNER.equals(actor.getRole());
        String effectiveStatus = status == null || status.isBlank() ? null : status;
        Long ownerOnly = null;
        Long visibleToMemberId = null;
        if (effectiveStatus == null) {
            // 未指定状态＝“全部状态”（接口合同：组织负责人见全部状态）：
            // 组织负责人不加限制；其他成员见已确认活动与本人负责的未发布活动（S06）。
            if (!orgOwner) {
                visibleToMemberId = actor.getId();
            }
        } else if (!orgOwner
            && (Activity.STATUS_DRAFT.equals(effectiveStatus)
                || Activity.STATUS_PENDING_APPROVAL.equals(effectiveStatus))) {
            // 指定未发布状态时，非组织负责人只能看到本人负责的活动
            ownerOnly = actor.getId();
        }
        long total = activityMapper.countPage(orgId, blankToNull(query), effectiveStatus, ownerOnly,
            visibleToMemberId);
        List<ActivityItem> items = activityMapper
            .listPage(orgId, blankToNull(query), effectiveStatus, ownerOnly, visibleToMemberId,
                (long) (page - 1) * pageSize, pageSize)
            .stream()
            .map(this::toItem)
            .toList();
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

    /** 编辑草稿：组织负责人或活动负责人；PATCH 仅更新显式字段；S08 支持携带期望版本做乐观锁。 */
    @Transactional
    public ActivityItem updateDraft(Long accountId, Long orgId, Long activityId, Activity patch) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        // S08 锁协议：活动自身写入取活动行排他锁，再访问子记录（组织 → 活动 → 子记录）
        activityMapper.lockActivity(activityId);
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
        // F05：编辑必须携带期望版本；缺失版本不再静默覆盖（开发规范：编辑请求携带 version）
        requireVersion(patch.getVersion());
        // 状态机（P2.2/P2.3）：待确认期不改字段；已确认活动仅组织负责人可改简介，关键内容须先撤回到草稿。
        if ("PENDING_APPROVAL".equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动待确认中，请先撤回再修改。");
        }
        if ("APPROVED".equals(activity.getStatus())) {
            boolean onlyDescription = patch.getName() == null && patch.getOwnerMemberId() == null
                && patch.getStartTime() == null && patch.getEndTime() == null
                && patch.getLocation() == null && patch.getBudget() == null
                && patch.getRegistrationScope() == null && patch.getCapacity() == null
                && patch.getRegistrationStart() == null && patch.getRegistrationEnd() == null;
            if (!onlyDescription || !isOrgOwner) {
                throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作",
                    "已确认活动的关键内容须先撤回到草稿再修改。");
            }
        }
        if (patch.getOwnerMemberId() != null) {
            if (!isOrgOwner) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以变更活动负责人。");
            }
            validateOwnerMember(orgId, patch.getOwnerMemberId());
        }
        // S07：已有报名后禁止切换内部／公开范围，避免身份冲突（计划书 6.1）
        if (patch.getRegistrationScope() != null
            && !patch.getRegistrationScope().equals(activity.getRegistrationScope())) {
            int existing = registrationMapper.countAnyByActivity(activityId);
            if (existing > 0) {
                throw conflict("ACTIVITY_SCOPE_LOCKED", "报名范围已锁定",
                    "该活动已有 " + existing + " 条报名记录，不能再切换内部／公开范围。");
            }
        }
        // S07：名额下限校验放在 Service，便于给出可读提示（早于时间合并校验）
        if (patch.getCapacity() != null) {
            int approved = registrationMapper.countApproved(activityId);
            if (patch.getCapacity() < approved) {
                throw conflict("ACTIVITY_CAPACITY_BELOW_APPROVED", "名额不能低于已通过报名数",
                    "已有 " + approved + " 条报名通过，新名额不得小于该数量。");
            }
        }
        // 合并后校验时间顺序：以编辑后的整体状态判断
        Activity merged = mergeForValidation(activity, patch);
        validateTimes(merged);
        if (patch.getBudget() != null && patch.getBudget().signum() < 0) {
            throw fieldError("budget", "预算不能为负数");
        }
        // P2.3／P5.3：预算下限校验——新预算不得小于当前已确认支出合计（计划书 6.1）
        if (patch.getBudget() != null) {
            java.math.BigDecimal approvedExpense =
                expenseMapper.sumAmountByStatus(activityId, com.example.activity.modules.expense.Expense.STATUS_APPROVED);
            if (approvedExpense != null && patch.getBudget().compareTo(approvedExpense) < 0) {
                throw conflict("ACTIVITY_BUDGET_BELOW_APPROVED", "预算不能低于已确认支出",
                    "该活动已确认支出 " + approvedExpense.setScale(2).toPlainString()
                        + " 元，新预算不得小于该金额。");
            }
        }
        if (patch.getCapacity() != null && patch.getCapacity() <= 0) {
            throw fieldError("capacity", "名额必须为正整数");
        }
        // 状态守卫：按当前实际状态限定更新（草稿自由编辑；已确认仅简介）
        patch.setStatus(activity.getStatus());
        if (activityMapper.updateDraft(patch) != 1) {
            // S08／F05：版本前置条件下影响行数为 0 即版本冲突（状态守卫不匹配时同样要求刷新）
            Activity current = activityMapper.findById(activityId);
            if (current == null) {
                throw notFound();
            }
            throw conflict("EDIT_CONFLICT", "内容已被他人修改",
                "该活动已由其他操作保存（当前版本 " + current.getVersion()
                    + "，你基于版本 " + patch.getVersion() + "），请刷新后重新编辑。");
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

    /** S06：统一可见性——未发布（草稿／待确认）仅 OWNER 与活动负责人；已确认对全部有效成员可见。 */
    private boolean canSeeDraft(Activity activity, Long accountId) {
        return activity.isVisibleTo(memberMapper.findActive(activity.getOrgId(), accountId), accountId);
    }

    private ActivityItem toItem(Activity activity) {
        return new ActivityItem(
            activity.getId(), activity.getName(), activity.getDescription(),
            String.valueOf(activity.getOwnerMemberId()),
            memberMapper.findUsernameByMemberId(activity.getOwnerMemberId()), activity.getStatus(),
            activity.getProposal(), activity.getReviewNote(), activity.getSubmittedBy(),
            activity.getConfirmedBy(), activity.getConfirmType(), activity.getConfirmedAt(),
            activity.getStartTime(), activity.getEndTime(),
            activity.getLocation(), activity.getBudget() == null
                ? "0.00" : activity.getBudget().setScale(2).toPlainString(),
            activity.getRegistrationScope(), activity.getCapacity(),
            activity.getRegistrationStart(), activity.getRegistrationEnd(), activity.isRegistrationOpen(),
            activity.getVersion(),
            activity.getStartedAt(), activity.getEndedAt(), activity.getArchivedAt(), activity.getCancelledAt(),
            activity.getCancelReason(), activity.getReopenReason(), activity.getReopenCount(),
            activity.getSummary(), activity.getSummaryConfirmedAt(), activity.getSummaryConfirmedBy(),
            activity.getCreatedAt(), activity.getUpdatedAt());
    }

    /** P6：站内提示只有必要摘要；接收人按当次操作固定（计划书 6.7）；来源操作唯一防重。 */
    private void notifyIntent(Long recipientAccountId, String type, Long activityId, String payload,
        String sourceKey) {
        if (recipientAccountId == null) {
            return;
        }
        notificationMapper.insert(recipientAccountId, type, activityId,
            payload.length() > 500 ? payload.substring(0, 500) : payload, sourceKey);
    }

    // ==================== P6：执行、取消、总结与归档 ====================

    /**
     * 启动执行（P6.1）：组织负责人或活动负责人明确启动，检查合法状态与报名审核已结束；
     * 不根据报名开关或时间自动跳转，也不生成到场记录。启动时固定已批准报名者作为反馈资格依据。
     */
    @Transactional
    public ActivityItem startExecution(Long accountId, Long orgId, Long activityId, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireManageable(orgId, activityId, actor, accountId);
        if (!Activity.STATUS_APPROVED.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许启动执行",
                "只有已确认且未开始的活动可以启动执行。");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        long pending = registrationMapper.countByActivity(activityId, "PENDING");
        if (pending > 0) {
            throw conflict("REGISTRATION_PENDING_REMAINS", "仍有待审核报名",
                "还有 " + pending + " 条待审核报名未处理，请先批准或驳回后再启动执行。");
        }
        String snapshotHash = sha256("registrations:"
            + registrationMapper.listApprovedAccountIds(activityId).toString());
        if (activityMapper.startExecution(activityId, orgId, snapshotHash, version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许启动执行", "活动状态已变化，请刷新后重试。");
        }
        feedbackService.snapshotApprovedRegistrants(activityId);
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_EXECUTION_STARTED", "activity:" + activityId,
            null, java.util.Map.of("snapshot", snapshotHash));
        return toItem(activityMapper.findById(activityId));
    }

    /**
     * 结束执行（P6.1）：执行中 → 复盘中；未完成任务不自动标为完成，
     * 并在此时固定活动负责人与未取消任务承担者的反馈资格依据（计划书 6.5）。
     */
    @Transactional
    public ActivityItem finishExecution(Long accountId, Long orgId, Long activityId, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireManageable(orgId, activityId, actor, accountId);
        if (!Activity.STATUS_EXECUTING.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许结束执行", "只有执行中的活动可以进入复盘。");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        if (activityMapper.finishExecution(activityId, orgId, version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许结束执行", "活动状态已变化，请刷新后重试。");
        }
        List<Long> assigneeMemberIds = taskMapper.listUncancelledAssigneeMemberIds(activityId);
        List<Long> assigneeAccountIds = new java.util.ArrayList<>();
        for (Long memberId : assigneeMemberIds) {
            OrganizationMember assignee = memberMapper.findById(memberId);
            if (assignee != null) {
                assigneeAccountIds.add(assignee.getAccountId());
            }
        }
        OrganizationMember ownerMember = memberMapper.findById(activity.getOwnerMemberId());
        feedbackService.snapshotExecutionRoles(activityId,
            ownerMember == null ? null : ownerMember.getAccountId(), assigneeAccountIds);
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_EXECUTION_FINISHED", "activity:" + activityId,
            null, java.util.Map.of("assignees", assigneeAccountIds.size()));
        return toItem(activityMapper.findById(activityId));
    }

    /**
     * 取消活动（P6.2）：仅组织负责人填写原因后取消；停止报名、关闭待审、有效报名转为活动取消结果，
     * 未完成任务转取消，保留已发生费用，向受影响账号写提示来源；重复取消不重复处理。
     */
    @Transactional
    public ActivityItem cancel(Long accountId, Long orgId, Long activityId, String reason, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireById(orgId, activityId);
        if (Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
            // 重复取消不产生第二次业务结果
            return toItem(activity);
        }
        if (Activity.STATUS_ARCHIVED.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许取消", "已归档活动不能取消，请先重新打开。");
        }
        if (Activity.STATUS_DRAFT.equals(activity.getStatus())
            || Activity.STATUS_PENDING_APPROVAL.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许取消",
                "未确认的活动请使用删除草稿或驳回处理。");
        }
        if (reason == null || reason.isBlank()) {
            throw fieldError("reason", "取消活动必须说明原因");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        if (activityMapper.cancel(activityId, orgId, reason.trim(), version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许取消", "活动状态已变化，请刷新后重试。");
        }
        List<Long> affected = new java.util.ArrayList<>();
        for (com.example.activity.modules.registration.Registration registration
            : registrationMapper.listCancellable(activityId)) {
            if (registrationMapper.cancel(registration.getId(), "CANCELLED_ORG", accountId,
                    "活动取消：" + reason.trim()) == 1) {
                affected.add(registration.getAccountId());
            }
        }
        int cancelledTasks = taskMapper.cancelOpenTasks(activityId);
        for (Long recipient : affected) {
            notifyIntent(recipient, "ACTIVITY_CANCELLED", activityId,
                "活动「" + activity.getName() + "」已取消：" + reason.trim() + "（负责人将处理遗留事项）",
                "activity:" + activityId + ":cancelled");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_CANCELLED", "activity:" + activityId, reason,
            java.util.Map.of("registrations", affected.size(), "tasks", cancelledTasks));
        return toItem(activityMapper.findById(activityId));
    }

    /** 保存总结草稿（P6.4）：内容变化即作废上一次确认，需重新核对来源后确认。 */
    @Transactional
    public ActivityItem saveSummary(Long accountId, Long orgId, Long activityId, String summary, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireManageable(orgId, activityId, actor, accountId);
        if (!Activity.STATUS_REVIEWING.equals(activity.getStatus())
            && !Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许编写总结",
                "总结在复盘期或取消后处理遗留事项时编写。");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        if (summary == null || summary.isBlank()) {
            throw fieldError("summary", "总结内容不能为空");
        }
        if (activityMapper.updateSummary(activityId, orgId, summary.trim(), version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许编写总结", "活动状态已变化，请刷新后重试。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_SUMMARY_SAVED", "activity:" + activityId, null, null);
        return toItem(activityMapper.findById(activityId));
    }

    /**
     * 确认总结（P6.4）：保存确认人与当时的来源摘要；来源随后变化即与现值不一致，
     * 归档前必须重新核对并确认（后端架构 5.4）。
     */
    @Transactional
    public ActivityItem confirmSummary(Long accountId, Long orgId, Long activityId, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireManageable(orgId, activityId, actor, accountId);
        if (activity.getSummary() == null || activity.getSummary().isBlank()) {
            throw conflict("SUMMARY_REQUIRED", "尚未编写总结", "请先保存总结内容再确认。");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        String sourceHash = summarySourceHash(activity);
        if (activityMapper.confirmSummary(activityId, orgId, accountId, sourceHash, version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许确认总结", "活动状态已变化，请刷新后重试。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_SUMMARY_CONFIRMED", "activity:" + activityId,
            null, java.util.Map.of("sourceHash", sourceHash));
        return toItem(activityMapper.findById(activityId));
    }

    /**
     * 归档（P6.5）：仅组织负责人；正常活动检查报名审核已结束、费用无待审、总结已确认且来源未变化、
     * 任务与物资已结束或有处置说明；取消活动检查遗留费用与取消说明。归档后普通写入全部关闭。
     */
    @Transactional
    public ActivityItem archive(Long accountId, Long orgId, Long activityId, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireById(orgId, activityId);
        if (Activity.STATUS_ARCHIVED.equals(activity.getStatus())) {
            return toItem(activity);
        }
        if (!Activity.STATUS_REVIEWING.equals(activity.getStatus())
            && !Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许归档",
                "只有复盘中或已取消的活动可以归档。");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        List<String> blocking = new java.util.ArrayList<>();
        long pendingRegistrations = registrationMapper.countByActivity(activityId, "PENDING");
        if (pendingRegistrations > 0) {
            blocking.add("待审核报名 " + pendingRegistrations + " 条");
        }
        java.math.BigDecimal pendingExpense =
            expenseMapper.sumAmountByStatus(activityId, com.example.activity.modules.expense.Expense.STATUS_PENDING);
        if (pendingExpense != null && pendingExpense.signum() > 0) {
            blocking.add("待确认费用 " + pendingExpense.setScale(2).toPlainString() + " 元");
        }
        int openTasks = taskMapper.countOpenByActivity(activityId);
        if (openTasks > 0) {
            blocking.add("未结束任务 " + openTasks + " 个");
        }
        if (activity.getSummary() == null || activity.getSummary().isBlank()
            || activity.getSummaryConfirmedAt() == null) {
            blocking.add("总结尚未确认");
        } else if (!summarySourceHash(activity).equals(activity.getSummarySourceHash())) {
            blocking.add("总结确认后来源已变化，需重新核对并确认");
        }
        if (Activity.STATUS_CANCELLED.equals(activity.getStatus())
            && (activity.getCancelReason() == null || activity.getCancelReason().isBlank())) {
            blocking.add("取消说明缺失");
        }
        if (!blocking.isEmpty()) {
            throw conflict("ARCHIVE_BLOCKED", "归档条件未满足",
                "归档前需要处理：" + String.join("、", blocking) + "。");
        }
        if (activityMapper.archive(activityId, orgId, activity.getStatus(), version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许归档", "活动状态已变化，请刷新后重试。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_ARCHIVED", "activity:" + activityId, null, null);
        return toItem(activityMapper.findById(activityId));
    }

    /**
     * 重新打开（P6.5）：组织负责人填写理由；正常活动回到复盘，取消活动回到取消处理；
     * 不恢复报名与反馈入口，历史确认快照保留（重新确认需再次确认总结）。
     */
    @Transactional
    public ActivityItem reopen(Long accountId, Long orgId, Long activityId, String reason, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = requireById(orgId, activityId);
        if (!Activity.STATUS_ARCHIVED.equals(activity.getStatus())
            && !Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许重新打开",
                "只有已归档活动可以重新打开；已取消活动可在此回到取消处理。");
        }
        if (reason == null || reason.isBlank()) {
            throw fieldError("reason", "重新打开必须说明理由");
        }
        if (!activity.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "活动已被他人修改", "活动版本已变化，请刷新后重试。");
        }
        String toStatus = Activity.STATUS_ARCHIVED.equals(activity.getStatus())
            ? Activity.STATUS_REVIEWING : Activity.STATUS_CANCELLED;
        if (activityMapper.reopen(activityId, orgId, activity.getStatus(), toStatus, reason.trim(), version) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许重新打开", "活动状态已变化，请刷新后重试。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_REOPENED", "activity:" + activityId, reason,
            java.util.Map.of("toStatus", toStatus));
        return toItem(activityMapper.findById(activityId));
    }

    // ---------- 执行记录 ----------

    /** P6.1：执行记录只追加；执行中、复盘期与取消遗留处理期间可写，归档后只读。 */
    @Transactional
    public ExecutionRecordItem addExecutionRecord(
        Long accountId, Long orgId, Long activityId, String category, String content) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivityShared(activityId);
        Activity activity = requireManageable(orgId, activityId, actor, accountId);
        if (!activity.isExecutionRecordable()) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许记录执行情况",
                "执行记录在执行中、复盘期或取消后的遗留处理期间填写。");
        }
        if (!ExecutionRecord.CATEGORIES.contains(category)) {
            throw fieldError("category", "未知的执行记录类型");
        }
        if (content == null || content.isBlank()) {
            throw fieldError("content", "执行记录内容不能为空");
        }
        ExecutionRecord record = new ExecutionRecord();
        record.setOrgId(orgId);
        record.setActivityId(activityId);
        record.setCategory(category);
        record.setContent(content.trim());
        record.setRecordedBy(accountId);
        executionRecordMapper.insert(record);
        auditService.record(accountId, "ACTIVITY", "EXECUTION_RECORD_ADDED", "activity:" + activityId,
            null, java.util.Map.of("category", category));
        return toItem(executionRecordMapper.listByActivity(activityId).getLast());
    }

    /** 执行记录列表：活动可见性适用（未发布活动仅负责人可见）。 */
    public List<ExecutionRecordItem> listExecutionRecords(Long accountId, Long orgId, Long activityId) {
        requireVisibleContext(accountId, orgId, activityId);
        return executionRecordMapper.listByActivity(activityId).stream().map(this::toItem).toList();
    }

    public record ExecutionRecordItem(
        Long recordId, String category, String content, Long recordedBy, String recordedByUsername,
        java.time.OffsetDateTime createdAt) {
    }

    private ExecutionRecordItem toItem(ExecutionRecord record) {
        return new ExecutionRecordItem(record.getId(), record.getCategory(), record.getContent(),
            record.getRecordedBy(), resolveUsername(record.getRecordedBy()), record.getCreatedAt());
    }

    // ---------- P8：AI 文本辅助的授权输入投影 ----------

    /** 生成类型标识（与 modules/ai 生成记录的类型一致）。 */
    public static final String AI_TYPE_SUMMARY = "ACTIVITY_SUMMARY";
    public static final String AI_TYPE_PLAN = "ACTIVITY_PLAN";

    /** 单段自由文本进入模型前的截断长度与列表条数上限：只发送本次生成必需的信息。 */
    private static final int AI_TEXT_LIMIT = 1500;
    private static final int AI_LIST_LIMIT = 15;

    /**
     * AI 授权输入投影（P8.2／P8.3）：来源摘要、事实文本、缺失项与输入范围说明。
     *
     * <p>来源摘要由本侧计算，排除总结自身、AI 记录、通知与访问日志，避免保存草稿就让来源失效；
     * 查询生成记录时用同一投影重算，即可判断生成后来源是否变化。
     */
    public record AiInput(String sourceHash, String facts, List<String> missing, List<String> scope) {
    }

    /**
     * 生成前的授权校验与输入投影：短事务内取锁并重读身份与活动，未发布活动不可见即 404，
     * 非负责人 403，状态不符 409；方案生成只在草稿期，总结生成只在复盘期或取消遗留处理期。
     */
    @Transactional
    public AiInput aiInput(Long accountId, Long orgId, Long activityId, String type) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivityShared(activityId);
        if (AI_TYPE_SUMMARY.equals(type)) {
            Activity activity = requireManageable(orgId, activityId, actor, accountId);
            if (!Activity.STATUS_REVIEWING.equals(activity.getStatus())
                && !Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
                throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不能生成总结",
                    "总结生成在复盘期或取消后处理遗留事项时使用。");
            }
            return summaryAiInput(activity);
        }
        if (AI_TYPE_PLAN.equals(type)) {
            Activity activity = requireById(orgId, activityId);
            if (!canSeeDraft(activity, accountId)) {
                throw notFound();
            }
            boolean allowed = OrganizationMember.ROLE_OWNER.equals(actor.getRole())
                || actor.getId().equals(activity.getOwnerMemberId());
            if (!allowed) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以使用方案生成。");
            }
            if (!Activity.STATUS_DRAFT.equals(activity.getStatus())) {
                throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不能生成方案",
                    "方案生成只在活动草稿的编辑期可用；保存方案仍走原有草稿流程。");
            }
            return planAiInput(activity);
        }
        throw fieldError("type", "未知的生成类型");
    }

    /** 总结输入：活动事实、已确认方案、任务、报名、执行记录、已确认费用与反馈，缺失项显式列出。 */
    private AiInput summaryAiInput(Activity activity) {
        Long activityId = activity.getId();
        StringBuilder facts = new StringBuilder();
        List<String> missing = new java.util.ArrayList<>();
        List<String> scope = new java.util.ArrayList<>();
        facts.append("活动名称：").append(activity.getName()).append('\n');
        facts.append("活动说明／目标：").append(
            activity.getDescription() == null || activity.getDescription().isBlank() ? "未记录" : activity.getDescription())
            .append('\n');
        if (activity.getDescription() == null || activity.getDescription().isBlank()) {
            missing.add("活动目标（活动说明为空）");
        }
        facts.append("活动状态：").append(statusLabel(activity.getStatus())).append('\n');
        facts.append("活动时间：").append(activity.getStartTime()).append(" 至 ").append(activity.getEndTime()).append('\n');
        facts.append("活动地点：").append(
            activity.getLocation() == null || activity.getLocation().isBlank() ? "未记录" : activity.getLocation())
            .append('\n');
        if (activity.getLocation() == null || activity.getLocation().isBlank()) {
            missing.add("活动地点");
        }
        facts.append("活动预算：").append(money(activity.getBudget())).append(" 元\n");
        facts.append("报名范围：")
            .append(Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope()) ? "公开报名" : "仅组织成员")
            .append("；名额：").append(activity.getCapacity() == null ? "未设置" : activity.getCapacity()).append('\n');
        String proposal = activity.getProposal();
        facts.append("已确认方案：").append(
            proposal == null || proposal.isBlank() ? "未记录" : truncate(proposal, AI_TEXT_LIMIT)).append('\n');
        if (proposal == null || proposal.isBlank()) {
            missing.add("已确认方案");
        }
        scope.add("活动基本信息（名称、说明、状态、时间、地点、预算、报名范围与名额）");
        scope.add(proposal == null || proposal.isBlank() ? "已确认方案：未记录" : "已确认方案全文");

        appendTasks(facts, missing, scope, activityId);
        appendRegistrations(facts, missing, scope, activityId);
        appendExecutionRecords(facts, missing, scope, activityId);
        appendExpenses(facts, missing, scope, activityId);
        appendFeedback(facts, missing, scope, activityId);
        facts.append("\n以上人数、金额与比例均为系统按当前记录计算的结果。\n");
        scope.add("已排除：参与者联系方式、账号凭据、通知与访问日志、AI 生成记录自身");
        String hash = sha256(summarySourceHash(activity) + "|projection:" + facts);
        return new AiInput(hash, facts.toString(), List.copyOf(missing), List.copyOf(scope));
    }

    /** 方案输入：活动已有记录作为事实参考，条件由调用方在请求中给出。 */
    private AiInput planAiInput(Activity activity) {
        StringBuilder facts = new StringBuilder();
        List<String> missing = new java.util.ArrayList<>();
        facts.append("活动名称：").append(activity.getName()).append('\n');
        facts.append("活动说明／目标：").append(
            activity.getDescription() == null || activity.getDescription().isBlank() ? "未记录" : activity.getDescription())
            .append('\n');
        if (activity.getDescription() == null || activity.getDescription().isBlank()) {
            missing.add("活动目标（活动说明为空）");
        }
        facts.append("活动时间：").append(activity.getStartTime()).append(" 至 ").append(activity.getEndTime()).append('\n');
        facts.append("活动地点：").append(
            activity.getLocation() == null || activity.getLocation().isBlank() ? "未记录" : activity.getLocation())
            .append('\n');
        facts.append("活动预算：").append(money(activity.getBudget())).append(" 元\n");
        facts.append("报名范围：")
            .append(Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope()) ? "公开报名" : "仅组织成员")
            .append("；名额：").append(activity.getCapacity() == null ? "未设置" : activity.getCapacity()).append('\n');
        String proposal = activity.getProposal();
        facts.append("当前方案草稿：").append(
            proposal == null || proposal.isBlank() ? "未填写" : truncate(proposal, AI_TEXT_LIMIT)).append('\n');
        List<String> scope = new java.util.ArrayList<>();
        scope.add("活动基本信息（名称、说明、时间、地点、预算、报名范围与名额）");
        scope.add(proposal == null || proposal.isBlank() ? "当前方案草稿：未填写" : "当前方案草稿全文");
        scope.add("本次填写的生成条件（主题、目标、对象、人数、预算、时间地点、补充限制）");
        scope.add("已排除：参与者联系方式、账号凭据、通知与访问日志、任务与经费明细");
        String hash = sha256("plan|" + facts);
        return new AiInput(hash, facts.toString(), List.copyOf(missing), List.copyOf(scope));
    }

    private void appendTasks(StringBuilder facts, List<String> missing, List<String> scope, Long activityId) {
        List<com.example.activity.modules.task.Task> tasks = new java.util.ArrayList<>(taskMapper.listByActivity(activityId));
        tasks.sort(java.util.Comparator.comparing(com.example.activity.modules.task.Task::getId));
        long open = tasks.stream().filter(t -> !com.example.activity.modules.task.Task.STATUS_COMPLETED.equals(t.getStatus())
            && !com.example.activity.modules.task.Task.STATUS_CANCELLED.equals(t.getStatus())).count();
        if (tasks.isEmpty()) {
            missing.add("任务记录");
        } else {
            facts.append("任务汇总：共 ").append(tasks.size()).append(" 个（未开始 ")
                .append(tasks.stream().filter(t -> com.example.activity.modules.task.Task.STATUS_PENDING.equals(t.getStatus())).count())
                .append("、进行中 ")
                .append(tasks.stream().filter(t -> com.example.activity.modules.task.Task.STATUS_IN_PROGRESS.equals(t.getStatus())).count())
                .append("、已完成 ")
                .append(tasks.stream().filter(t -> com.example.activity.modules.task.Task.STATUS_COMPLETED.equals(t.getStatus())).count())
                .append("、已取消 ")
                .append(tasks.stream().filter(t -> com.example.activity.modules.task.Task.STATUS_CANCELLED.equals(t.getStatus())).count())
                .append("），未完成 ")
                .append(open).append(" 个\n任务明细（最多 ").append(AI_LIST_LIMIT).append(" 条）：\n");
            OffsetDateTime now = OffsetDateTime.now();
            for (com.example.activity.modules.task.Task task : tasks.subList(0, Math.min(AI_LIST_LIMIT, tasks.size()))) {
                facts.append("- ").append(truncate(task.getTitle(), 80)).append('：').append(taskStatusLabel(task.getStatus()));
                if (task.getDueAt() != null) {
                    facts.append("，截止 ").append(task.getDueAt());
                    if (!com.example.activity.modules.task.Task.STATUS_COMPLETED.equals(task.getStatus())
                        && !com.example.activity.modules.task.Task.STATUS_CANCELLED.equals(task.getStatus())
                        && task.getDueAt().isBefore(now)) {
                        facts.append("（已逾期）");
                    }
                }
                facts.append('\n');
            }
            scope.add("任务 " + tasks.size() + " 个（标题、状态、截止时间与逾期情况）");
        }
    }

    private void appendRegistrations(StringBuilder facts, List<String> missing, List<String> scope, Long activityId) {
        long approved = registrationMapper.countByActivity(activityId, "APPROVED");
        long pending = registrationMapper.countByActivity(activityId, "PENDING");
        long rejected = registrationMapper.countByActivity(activityId, "REJECTED");
        long cancelled = registrationMapper.countByActivity(activityId, "CANCELLED_SELF")
            + registrationMapper.countByActivity(activityId, "CANCELLED_ORG");
        long total = approved + pending + rejected + cancelled;
        if (total == 0) {
            missing.add("报名记录");
            return;
        }
        facts.append("报名汇总（系统统计）：已通过 ").append(approved).append("、待审核 ").append(pending)
            .append("、已驳回 ").append(rejected).append("、已取消 ").append(cancelled).append('\n');
        scope.add("报名汇总（已通过／待审核／已驳回／已取消人数，不含参与者姓名与联系方式）");
    }

    private void appendExecutionRecords(
        StringBuilder facts, List<String> missing, List<String> scope, Long activityId) {
        List<ExecutionRecord> records = new java.util.ArrayList<>(executionRecordMapper.listByActivity(activityId));
        if (records.isEmpty()) {
            missing.add("执行记录");
            return;
        }
        facts.append("执行记录（最多 ").append(AI_LIST_LIMIT).append(" 条）：\n");
        for (ExecutionRecord record : records.subList(0, Math.min(AI_LIST_LIMIT, records.size()))) {
            facts.append("- ").append(record.getCategory()).append('：')
                .append(truncate(record.getContent(), 200)).append('\n');
        }
        scope.add("执行记录 " + records.size() + " 条（类型与内容）");
    }

    private void appendExpenses(StringBuilder facts, List<String> missing, List<String> scope, Long activityId) {
        String approved = money(expenseMapper.sumAmountByStatus(
            activityId, com.example.activity.modules.expense.Expense.STATUS_APPROVED));
        String pending = money(expenseMapper.sumAmountByStatus(
            activityId, com.example.activity.modules.expense.Expense.STATUS_PENDING));
        List<com.example.activity.modules.expense.Expense> items = expenseMapper.listByActivity(activityId, null, 0, 200);
        List<com.example.activity.modules.expense.Expense> confirmed = items.stream()
            .filter(item -> com.example.activity.modules.expense.Expense.STATUS_APPROVED.equals(item.getStatus()))
            .sorted(java.util.Comparator.comparing(com.example.activity.modules.expense.Expense::getId))
            .toList();
        facts.append("经费（系统统计）：已确认支出 ").append(approved)
            .append(" 元、待确认 ").append(pending).append(" 元\n");
        if (confirmed.isEmpty()) {
            missing.add("已确认费用明细");
        } else {
            facts.append("已确认支出明细（最多 ").append(AI_LIST_LIMIT).append(" 条）：\n");
            for (com.example.activity.modules.expense.Expense item
                : confirmed.subList(0, Math.min(AI_LIST_LIMIT, confirmed.size()))) {
                facts.append("- ").append(truncate(item.getPurpose(), 80)).append(' ')
                    .append(money(item.getAmount())).append(" 元（").append(item.getCategory())
                    .append("，").append(item.getIncurredOn()).append("）\n");
            }
        }
        scope.add("经费汇总与已确认支出明细（用途、金额、分类与发生日期，不含凭证文件）");
    }

    private void appendFeedback(StringBuilder facts, List<String> missing, List<String> scope, Long activityId) {
        com.example.activity.modules.feedback.FeedbackService.FeedbackDigest digest =
            feedbackService.digest(activityId, 10, 200);
        if (digest.count() == 0) {
            missing.add("反馈");
            facts.append("反馈：暂无反馈\n");
            return;
        }
        facts.append("反馈（系统统计）：").append(digest.count()).append(" 份，平均分 ").append(digest.averageRating())
            .append('\n');
        if (!digest.comments().isEmpty()) {
            facts.append("反馈文字要点：\n");
            for (String comment : digest.comments()) {
                facts.append("- ").append(truncate(comment, 200)).append('\n');
            }
        }
        scope.add("反馈 " + digest.count() + " 份（份数、平均分与文字要点，不含反馈人身份）");
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
            .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "…";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case Activity.STATUS_DRAFT -> "草稿";
            case Activity.STATUS_PENDING_APPROVAL -> "待确认";
            case Activity.STATUS_APPROVED -> "筹备中";
            case Activity.STATUS_EXECUTING -> "执行中";
            case Activity.STATUS_REVIEWING -> "复盘中";
            case Activity.STATUS_ARCHIVED -> "已归档";
            case Activity.STATUS_CANCELLED -> "已取消";
            default -> status;
        };
    }

    private static String taskStatusLabel(String status) {
        return switch (status) {
            case com.example.activity.modules.task.Task.STATUS_PENDING -> "未开始";
            case com.example.activity.modules.task.Task.STATUS_IN_PROGRESS -> "进行中";
            case com.example.activity.modules.task.Task.STATUS_COMPLETED -> "已完成";
            case com.example.activity.modules.task.Task.STATUS_CANCELLED -> "已取消";
            default -> status;
        };
    }

    // ---------- 内部：P6 辅助 ----------

    private record VisibleContext(OrganizationMember actor, Activity activity) {
    }

    /** 未发布活动只有负责人可见；返回锁内重读后的身份与活动。 */
    private VisibleContext requireVisibleContext(Long accountId, Long orgId, Long activityId) {
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        Activity activity = requireById(orgId, activityId);
        if (!activity.isVisibleTo(actor, accountId)) {
            throw notFound();
        }
        return new VisibleContext(actor, activity);
    }

    /** 组织负责人或活动负责人；调用方已按锁顺序取锁。 */
    private Activity requireManageable(Long orgId, Long activityId, OrganizationMember actor, Long accountId) {
        Activity activity = requireById(orgId, activityId);
        boolean allowed = OrganizationMember.ROLE_OWNER.equals(actor.getRole())
            || actor.getId().equals(activity.getOwnerMemberId());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问",
                "仅组织负责人或活动负责人可以执行此操作。");
        }
        return activity;
    }

    private Activity requireById(Long orgId, Long activityId) {
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)) {
            throw notFound();
        }
        return activity;
    }

    /**
     * 总结来源摘要（后端架构 5.4）：只取实际输入投影——活动事实、任务、报名、费用、执行记录与反馈，
     * 固定字段与稳定排序；排除总结自身、AI 记录、通知与访问日志，避免保存总结就让来源失效。
     */
    private String summarySourceHash(Activity activity) {
        StringBuilder canonical = new StringBuilder()
            .append("activity:").append(activity.getId())
            .append('|').append(activity.getName())
            .append('|').append(activity.getStartTime())
            .append('|').append(activity.getEndTime())
            .append('|').append(activity.getLocation())
            .append('|').append(activity.getBudget() == null ? "0.00" : activity.getBudget().toPlainString())
            .append('|').append(activity.getRegistrationScope())
            .append('|').append(activity.getCapacity())
            .append('|').append(activity.getStatus())
            .append('|').append(activity.getSummarySourceHash() == null ? "-" : "-");
        for (com.example.activity.modules.task.Task task : taskMapper.listByActivity(activity.getId())) {
            canonical.append("|task:").append(task.getId()).append(':').append(task.getStatus())
                .append(':').append(task.getAssigneeMemberId());
        }
        for (com.example.activity.modules.registration.Registration registration
            : registrationMapper.listByActivity(activity.getId(), null, 0, 1000)) {
            canonical.append("|reg:").append(registration.getId()).append(':').append(registration.getStatus());
        }
        canonical.append("|expenseApproved:")
            .append(expenseMapper.sumAmountByStatus(activity.getId(),
                com.example.activity.modules.expense.Expense.STATUS_APPROVED))
            .append("|expensePending:")
            .append(expenseMapper.sumAmountByStatus(activity.getId(),
                com.example.activity.modules.expense.Expense.STATUS_PENDING));
        canonical.append("|records:").append(executionRecordMapper.countByActivity(activity.getId()));
        canonical.append("|feedback:").append(feedbackService.summarySourcePart(activity.getId()));
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String resolveUsername(Long accountId) {
        return accountMapper.findUsernameById(accountId);
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效", "请求字段校验失败，请修改后重试。",
            List.of(new com.example.activity.common.FieldErrorItem(field, message)));
    }

    /** F05：编辑与审批请求必须携带期望版本，缺失即拒绝，不再按无版本兼容保存。 */
    private void requireVersion(Long version) {
        if (version == null) {
            throw fieldError("version", "请携带当前版本号（version）后再提交");
        }
    }

    /** F05：锁内比较请求版本与当前版本，过期请求返回 409 而不覆盖他人保存。 */
    private void requireCurrentVersion(Activity activity, Long version, String action) {
        if (!version.equals(activity.getVersion())) {
            throw conflict("EDIT_CONFLICT", "内容已被他人修改",
                action + "操作的版本已过期（当前版本 " + activity.getVersion()
                    + "，你基于版本 " + version + "），请刷新后重新操作。");
        }
    }


    /**
     * 报名开关（S02／P4.2）：组织负责人或活动负责人；仅已确认且未到开始时间可变更。
     * 关闭只停止新申请，不阻止既有申请的审核与合法取消（后端架构 5.2）。
     */
    @Transactional
    public ActivityItem setRegistrationOpen(Long accountId, Long orgId, Long activityId, boolean open) {
        organizationMapper.lockOrganization(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId) || !canSeeDraft(activity, accountId)) {
            throw notFound();
        }
        if (!OrganizationMember.ROLE_OWNER.equals(actor.getRole())
            && !actor.getId().equals(activity.getOwnerMemberId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问",
                "仅组织负责人或活动负责人可以变更报名开关。");
        }
        if (!Activity.STATUS_APPROVED.equals(activity.getStatus())) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "仅已确认活动可以开关报名。");
        }
        if (!activity.isBeforeStart()) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动已开始，不能再变更报名开关。");
        }
        if (activityMapper.updateRegistrationOpen(activityId, orgId, open) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        auditService.record(accountId, "ACTIVITY", open ? "ACTIVITY_REGISTRATION_OPENED" : "ACTIVITY_REGISTRATION_CLOSED",
            "activity:" + activityId, null, java.util.Map.of("registrationOpen", open));
        return toItem(activityMapper.findById(activityId));
    }

    /** 保存方案文稿：草稿期由组织负责人或活动负责人编写（P2.2，不依赖 AI 也能完成）。 */
    @Transactional
    public ActivityItem saveProposal(Long accountId, Long orgId, Long activityId, String content, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !canSeeDraft(activity, accountId)
            || !"DRAFT".equals(activity.getStatus())) {
            throw notFound();
        }
        boolean allowed = OrganizationMember.ROLE_OWNER.equals(actor.getRole())
            || actor.getId().equals(activity.getOwnerMemberId());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人或活动负责人可以编写方案。");
        }
        // F05：方案保存纳入与编辑相同的版本合同，锁内比较后条件更新
        requireCurrentVersion(activity, version, "活动方案");
        if (activityMapper.updateProposal(activityId, orgId, content, version) != 1) {
            throw conflict("EDIT_CONFLICT", "内容已被他人修改",
                "活动方案已被其他操作保存，请刷新后重新编辑。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_PROPOSAL_SAVED", "activity:" + activityId,
            null, java.util.Map.of("version", version));
        return toItem(activityMapper.findById(activityId));
    }

    /** 提交确认：草稿信息完备后进入待确认；提交者可以是活动负责人或组织负责人。 */
    @Transactional
    public ActivityItem submit(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !canSeeDraft(activity, accountId)
            || !"DRAFT".equals(activity.getStatus())) {
            throw notFound();
        }
        validateSubmission(activity);
        if (activityMapper.transitionStatus(activityId, orgId, "DRAFT", "PENDING_APPROVAL") != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        activityMapper.updateReview(activityId, orgId, null, accountId, null, null);
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_SUBMITTED", "activity:" + activityId, null,
            java.util.Map.of("name", activity.getName()));
        return toItem(activityMapper.findById(activityId));
    }

    /** 撤回：提交者或组织负责人把待确认活动撤回草稿。 */
    @Transactional
    public ActivityItem withdraw(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganizationShared(orgId);
        OrganizationMember actor = requireActiveMember(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !canSeeDraft(activity, accountId)
            || !"PENDING_APPROVAL".equals(activity.getStatus())) {
            throw notFound();
        }
        boolean allowed = OrganizationMember.ROLE_OWNER.equals(actor.getRole())
            || accountId.equals(activity.getSubmittedBy());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅提交者或组织负责人可以撤回。");
        }
        if (activityMapper.transitionStatus(activityId, orgId, "PENDING_APPROVAL", "DRAFT") != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_WITHDRAWN", "activity:" + activityId, null, null);
        return toItem(activityMapper.findById(activityId));
    }

    /** 通过：组织负责人确认（自行确认或审核他人提交均在此留痕）。 */
    @Transactional
    public ActivityItem approve(Long accountId, Long orgId, Long activityId, String note, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !"PENDING_APPROVAL".equals(activity.getStatus())) {
            throw notFound();
        }
        // F05：审批锁定被审阅的那一版；撤回重提后旧审批请求不再生效
        requireCurrentVersion(activity, version, "立项确认");
        if (activityMapper.transitionStatus(activityId, orgId, "PENDING_APPROVAL", "APPROVED") != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        // S07：记录确认时间，供撤回后识别“调整期待确认”与删除判定
        activityMapper.markConfirmed(activityId, orgId);
        String confirmType = accountId.equals(activity.getSubmittedBy()) ? "SELF" : "OTHER";
        activityMapper.updateReview(activityId, orgId, note, activity.getSubmittedBy(), accountId, confirmType);
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_APPROVED", "activity:" + activityId,
            note, java.util.Map.of("confirmType", confirmType));
        // F08：重新确认属关键活动变更，给既有报名者留下来源意图（计划书 6.1：重新通过后通知已报名者）
        for (Long recipient : registrationMapper.listNotifiableAccountIds(activityId)) {
            notifyIntent(recipient, "ACTIVITY_RECONFIRMED", activityId,
                "你报名的活动「" + activity.getName() + "」已重新确认，报名开关默认关闭，请以最新安排为准。",
                "activity:" + activityId + ":reconfirmed:" + activity.getVersion());
        }
        return toItem(activityMapper.findById(activityId));
    }

    /** 驳回：组织负责人写明原因后退回草稿。 */
    @Transactional
    public ActivityItem reject(Long accountId, Long orgId, Long activityId, String reason, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !"PENDING_APPROVAL".equals(activity.getStatus())) {
            throw notFound();
        }
        // F05：同审批，锁定被驳回的那一版
        requireCurrentVersion(activity, version, "立项确认");
        if (activityMapper.transitionStatus(activityId, orgId, "PENDING_APPROVAL", "DRAFT") != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        activityMapper.updateReview(activityId, orgId, reason, activity.getSubmittedBy(), null, null);
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_REJECTED", "activity:" + activityId, reason, null);
        return toItem(activityMapper.findById(activityId));
    }

    /** 撤回到草稿（P2.3 关键变更前置）：仅组织负责人。 */
    @Transactional
    public ActivityItem withdrawApproval(Long accountId, Long orgId, Long activityId, String reason, Long version) {
        requireVersion(version);
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !"APPROVED".equals(activity.getStatus())) {
            throw notFound();
        }
        // F05：撤回确认同样锁定被撤回的那一版，避免撤掉重新确认后的新版本
        requireCurrentVersion(activity, version, "撤回确认");
        if (activityMapper.transitionStatus(activityId, orgId, "APPROVED", "DRAFT") != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        // 计划书 6.1：撤回到草稿即暂停报名，重新确认后由负责人明确开启
        activityMapper.updateRegistrationOpen(activityId, orgId, false);
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_APPROVAL_WITHDRAWN", "activity:" + activityId,
            reason, null);
        return toItem(activityMapper.findById(activityId));
    }

    /**
     * 删除草稿：仅组织负责人，且仅“从未确认且无依赖”的初始草稿（计划书 6.1）。
     * S07：曾确认后撤回的活动保留；已产生任务／物资／附件／有效报名的草稿不得删除。
     */
    @Transactional
    public void deleteDraft(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganization(orgId);
        requireOwner(orgId, accountId);
        activityMapper.lockActivity(activityId);
        Activity activity = activityMapper.findById(activityId);
        if (activity == null || !activity.getOrgId().equals(orgId)
            || !"DRAFT".equals(activity.getStatus())) {
            throw notFound();
        }
        if (activity.isPendingReconfirmation()) { // S07：曾确认后撤回的活动保留
            throw conflict("ACTIVITY_DELETE_CONFLICT", "当前状态不允许此操作",
                "该活动曾通过立项确认，撤回后不能按初始草稿删除，请重新确认或另行取消。");
        }
        List<String> dependencies = describeDependencies(activityId);
        if (!dependencies.isEmpty()) {
            throw conflict("ACTIVITY_HAS_DEPENDENCIES", "存在关联记录，不能删除",
                "该草稿已有关联记录（" + String.join("、", dependencies) + "），请先处理后再删除。");
        }
        if (activityMapper.deleteDraft(activityId, orgId) != 1) {
            throw conflict("ACTIVITY_STATE_CONFLICT", "当前状态不允许此操作", "活动状态已变化，请刷新后查看。");
        }
        auditService.record(accountId, "ACTIVITY", "ACTIVITY_DRAFT_DELETED", "activity:" + activityId,
            null, java.util.Map.of("name", activity.getName()));
    }

    /** 删除前的关联记录清点（只读查询，不修改其他模块数据）。 */
    private List<String> describeDependencies(Long activityId) {
        List<String> found = new java.util.ArrayList<>();
        if (taskMapper.countByActivity(activityId) > 0) {
            found.add("任务");
        }
        if (materialMapper.countByActivity(activityId) > 0) {
            found.add("物资");
        }
        if (attachmentMapper.countByActivity(activityId) > 0) {
            found.add("附件");
        }
        if (registrationMapper.countEffective(activityId) > 0) {
            found.add("有效报名");
        }
        if (expenseMapper.countByActivityId(activityId) > 0) {
            found.add("费用登记");
        }
        return found;
    }

    /** 提交前完备性校验（P2.2）。 */
    private void validateSubmission(Activity activity) {
        List<String> missing = new java.util.ArrayList<>();
        if (activity.getName() == null || activity.getName().isBlank()) missing.add("name");
        if (activity.getStartTime() == null) missing.add("startTime");
        if (activity.getEndTime() == null) missing.add("endTime");
        if (activity.getLocation() == null || activity.getLocation().isBlank()) missing.add("location");
        if (activity.getDescription() == null || activity.getDescription().isBlank()) missing.add("description");
        if (activity.getProposal() == null || activity.getProposal().isBlank()) missing.add("proposal");
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SUBMISSION_INCOMPLETE", "提交信息不完备",
                "还有必填内容未填写：" + fieldLabels(missing),
                missing.stream().map(f -> new com.example.activity.common.FieldErrorItem(f, "为提交所必需")).toList());
        }
        validateTimes(activity);
        if (Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope())) {
            List<String> publicMissing = new java.util.ArrayList<>();
            if (activity.getCapacity() == null) publicMissing.add("capacity");
            if (activity.getRegistrationStart() == null) publicMissing.add("registrationStart");
            if (activity.getRegistrationEnd() == null) publicMissing.add("registrationEnd");
            if (!publicMissing.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SUBMISSION_INCOMPLETE", "提交信息不完备",
                    "公开报名需要名额与报名时间窗：" + fieldLabels(publicMissing),
                    publicMissing.stream().map(f -> new com.example.activity.common.FieldErrorItem(f, "公开报名所必需")).toList());
            }
        }
    }

    private String fieldLabels(List<String> fields) {
        java.util.Map<String, String> labels = java.util.Map.ofEntries(
            java.util.Map.entry("name", "活动主题"),
            java.util.Map.entry("startTime", "开始时间"),
            java.util.Map.entry("endTime", "结束时间"),
            java.util.Map.entry("location", "地点"),
            java.util.Map.entry("description", "活动简介"),
            java.util.Map.entry("proposal", "活动方案"),
            java.util.Map.entry("capacity", "名额"),
            java.util.Map.entry("registrationStart", "报名开始"),
            java.util.Map.entry("registrationEnd", "报名截止"));
        return fields.stream().map(f -> labels.getOrDefault(f, f)).collect(java.util.stream.Collectors.joining("、"));
    }

    private void requireOwner(Long orgId, Long accountId) {
        OrganizationMember member = requireActiveMember(orgId, accountId);
        if (!OrganizationMember.ROLE_OWNER.equals(member.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问", "仅组织负责人可以执行此操作。");
        }
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
