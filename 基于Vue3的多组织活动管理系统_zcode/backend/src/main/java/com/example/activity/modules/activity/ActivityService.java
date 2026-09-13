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
        com.example.activity.modules.notification.NotificationMapper notificationMapper) {
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
            activity.getVersion(), activity.getCreatedAt(), activity.getUpdatedAt());
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
            notificationMapper.insert(recipient, "ACTIVITY_RECONFIRMED", activityId,
                "你报名的活动「" + activity.getName() + "」已重新确认，报名开关默认关闭，请以最新安排为准。");
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
