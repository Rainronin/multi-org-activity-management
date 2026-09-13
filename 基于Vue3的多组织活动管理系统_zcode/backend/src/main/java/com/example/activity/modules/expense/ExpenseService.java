package com.example.activity.modules.expense;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import com.example.activity.modules.organization.OrganizationMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动经费（P5）：预算读取、支出登记、提交与确认、驳回与作废、更正与统计。
 *
 * <p>权限按计划书 7.4：登记与查看限组织负责人或本活动负责人；确认与作废仅组织负责人
 * （活动负责人兼任组织负责人时同样通过）。其他成员与外部参与者一律 404，不泄露存在性。
 *
 * <p>锁协议按后端架构 5.3：确认／作废取活动行排他锁后在锁内求当前有效已确认费用之和，
 * 据此判断超支；登记与提交只需组织 → 活动共享锁。
 */
@Service
public class ExpenseService {

    private static final String CODE_OVER_BUDGET = "EXPENSE_OVER_BUDGET";

    private final ExpenseMapper expenseMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.attachment.AttachmentMapper attachmentMapper;
    private final com.example.activity.modules.notification.NotificationMapper notificationMapper;
    private final AuditService auditService;

    public ExpenseService(
        ExpenseMapper expenseMapper,
        ActivityMapper activityMapper,
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.attachment.AttachmentMapper attachmentMapper,
        com.example.activity.modules.notification.NotificationMapper notificationMapper,
        AuditService auditService) {
        this.expenseMapper = expenseMapper;
        this.activityMapper = activityMapper;
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.attachmentMapper = attachmentMapper;
        this.notificationMapper = notificationMapper;
        this.auditService = auditService;
    }

    /** 明细项；金额与统计值统一按两位小数字符串下发，避免前端浮点误差。 */
    public record ExpenseItem(
        Long expenseId, Long activityId, String purpose, String amount, LocalDate incurredOn,
        String category, String handledNote, String noReceiptNote, String status,
        Long submittedBy, OffsetDateTime submittedAt, Long decidedBy, OffsetDateTime decidedAt,
        String rejectReason, String overBudgetNote, String voidReason, OffsetDateTime voidedAt,
        Long correctsExpenseId, String voucherCount, Long version) {
    }

    public record ExpensePage(List<ExpenseItem> items, int page, int pageSize, long total) {
    }

    /** P5.3 统计：预算、待确认支出、已确认支出与预算余额（余额允许为负）。 */
    public record ExpenseSummary(
        String budget, String pendingAmount, String approvedAmount, String balance, long total) {
    }

    private record Context(OrganizationMember actor, Activity activity, boolean orgOwner, boolean activityOwner) {
        boolean canRegister() {
            return orgOwner || activityOwner;
        }
    }

    // ---------- 读取 ----------

    /** 费用明细列表：仅管理者可读（计划书 7.4）。 */
    public ExpensePage list(Long accountId, Long orgId, Long activityId, int page, int pageSize, String status) {
        Context ctx = requireManageContext(accountId, orgId, activityId);
        String filter = status == null || status.isBlank() ? null : status;
        long total = expenseMapper.countByActivity(activityId, filter);
        List<ExpenseItem> items = expenseMapper
            .listByActivity(activityId, filter, (long) (page - 1) * pageSize, pageSize)
            .stream().map(this::toItem).toList();
        return new ExpensePage(items, page, pageSize, total);
    }

    /** 经费统计：只按有效已确认明细扣减，驳回／作废／草稿不计入。 */
    public ExpenseSummary summary(Long accountId, Long orgId, Long activityId) {
        Context ctx = requireManageContext(accountId, orgId, activityId);
        BigDecimal budget = ctx.activity().getBudget() == null ? BigDecimal.ZERO : ctx.activity().getBudget();
        BigDecimal pending = expenseMapper.sumAmountByStatus(activityId, Expense.STATUS_PENDING);
        BigDecimal approved = expenseMapper.sumAmountByStatus(activityId, Expense.STATUS_APPROVED);
        return new ExpenseSummary(
            money(budget), money(pending), money(approved), money(budget.subtract(approved)),
            expenseMapper.countByActivity(activityId, null));
    }

    /** 凭证目标校验：支出必须属于该活动且调用者可管理；供凭证上传／列表先行校验。 */
    public ExpenseItem requireVoucherTarget(Long accountId, Long orgId, Long activityId, Long expenseId) {
        requireManageContext(accountId, orgId, activityId);
        return toItem(requireExpense(orgId, activityId, expenseId));
    }

    // ---------- 登记（组织负责人或活动负责人） ----------

    /** 新建支出草稿：必须携带 Idempotency-Key（开发规范第三节：支出属可能重复产生费用的 POST）。 */
    @Transactional
    public ExpenseItem create(
        Long accountId, Long orgId, Long activityId, String idempotencyKey, Expense draft) {
        Context ctx = requireRegisterContext(accountId, orgId, activityId);
        String requestKey = requireIdempotencyKey(idempotencyKey);
        validateDraft(draft);
        String fingerprint = fingerprintOf(draft, null);
        Expense existing = expenseMapper.findByRequestKey(orgId, requestKey);
        if (existing != null) {
            return replayResult(existing, fingerprint);
        }
        draft.setOrgId(orgId);
        draft.setActivityId(activityId);
        draft.setStatus(Expense.STATUS_DRAFT);
        draft.setCreatedBy(accountId);
        draft.setRequestKey(requestKey);
        draft.setRequestFingerprint(fingerprint);
        try {
            expenseMapper.insert(draft);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            Expense committed = expenseMapper.findByRequestKey(orgId, requestKey);
            if (committed == null) {
                throw duplicate;
            }
            return replayResult(committed, fingerprint);
        }
        auditService.record(accountId, "EXPENSE", "EXPENSE_DRAFT_CREATED", "expense:" + draft.getId(),
            null, Map.of("activityId", activityId, "amount", money(draft.getAmount())));
        return toItem(expenseMapper.findById(draft.getId()));
    }

    /** 编辑：仅草稿与已驳回可改，必须携带期望版本。 */
    @Transactional
    public ExpenseItem update(
        Long accountId, Long orgId, Long activityId, Long expenseId, Expense patch) {
        requireRegisterContext(accountId, orgId, activityId);
        requireVersion(patch.getVersion());
        Expense expense = requireExpense(orgId, activityId, expenseId);
        if (!expense.isEditable()) {
            throw conflict("EXPENSE_STATE_CONFLICT", "支出状态不允许修改",
                "待确认与已确认的支出不能直接修改；已确认请先作废再建立更正记录。");
        }
        validateDraft(patch);
        if (expenseMapper.update(expenseId, patch.getPurpose(), patch.getAmount(), patch.getIncurredOn(),
                patch.getCategory(), patch.getHandledNote(), patch.getNoReceiptNote(),
                patch.getVersion()) != 1) {
            throw conflict("EDIT_CONFLICT", "支出已被他人修改",
                "该支出已由其他操作保存（当前版本 " + expense.getVersion() + "），请刷新后重新编辑。");
        }
        auditService.record(accountId, "EXPENSE", "EXPENSE_UPDATED", "expense:" + expenseId,
            null, Map.of("activityId", activityId));
        return toItem(expenseMapper.findById(expenseId));
    }

    /** 提交待确认：草稿或已驳回可提交；驳回后重提保留历史（版本递增）。 */
    @Transactional
    public ExpenseItem submit(Long accountId, Long orgId, Long activityId, Long expenseId, Long version) {
        requireRegisterContext(accountId, orgId, activityId);
        requireVersion(version);
        Expense expense = requireExpense(orgId, activityId, expenseId);
        if (!expense.isEditable()) {
            throw conflict("EXPENSE_STATE_CONFLICT", "支出状态不允许提交", "仅草稿或已驳回的支出可以提交。");
        }
        // 计划书 6.3：没有票据时必须填写无票说明（凭证以已登记的附件为准）
        if (attachmentMapper.countActiveByExpense(expenseId) == 0
            && blankToNull(expense.getNoReceiptNote()) == null) {
            throw fieldError("noReceiptNote", "未上传凭证时请填写无票说明");
        }
        if (!expense.getVersion().equals(version) || expenseMapper.transition(
                expenseId, expense.getStatus(), Expense.STATUS_PENDING, accountId, null, null, true, null) != 1) {
            throw conflict("EDIT_CONFLICT", "支出已被他人修改", "该支出状态或版本已变化，请刷新后重试。");
        }
        auditService.record(accountId, "EXPENSE", "EXPENSE_SUBMITTED", "expense:" + expenseId,
            null, Map.of("activityId", activityId));
        String activityName = requireActivityName(activityId);
        for (Long owner : memberMapper.listActiveOwnerAccountIds(orgId)) {
            if (!owner.equals(accountId)) {
                notifyIntent(owner, "EXPENSE_SUBMITTED", activityId,
                    "活动「" + activityName + "」有 1 笔支出待确认。", "expense:" + expenseId + ":submitted");
            }
        }
        return toItem(expenseMapper.findById(expenseId));
    }

    // ---------- 确认与作废（仅组织负责人） ----------

    /**
     * 确认支出：锁内求当前有效已确认费用之和，超出预算时必须由组织负责人填写说明后仍然确认；
     * 重复确认不重复累计（状态守卫），并发确认由活动行排他锁串行化。
     */
    @Transactional
    public ExpenseItem confirm(
        Long accountId, Long orgId, Long activityId, Long expenseId, String overBudgetNote, Long version) {
        Context ctx = requireOwnerContext(accountId, orgId, activityId);
        requireVersion(version);
        Expense expense = requireExpense(orgId, activityId, expenseId);
        if (!Expense.STATUS_PENDING.equals(expense.getStatus())) {
            throw conflict("EXPENSE_STATE_CONFLICT", "支出状态不允许确认", "仅待确认的支出可以确认。");
        }
        if (!expense.getVersion().equals(version)) {
            throw conflict("EDIT_CONFLICT", "支出已被他人修改", "该支出版本已变化，请刷新后重试。");
        }
        BigDecimal budget = ctx.activity().getBudget() == null ? BigDecimal.ZERO : ctx.activity().getBudget();
        BigDecimal approved = expenseMapper.sumAmountByStatus(activityId, Expense.STATUS_APPROVED);
        BigDecimal projected = approved.add(expense.getAmount());
        String note = blankToNull(overBudgetNote);
        if (projected.compareTo(budget) > 0 && note == null) {
            throw new ApiException(HttpStatus.CONFLICT, CODE_OVER_BUDGET, "支出超出预算",
                "本次确认后合计 " + money(projected) + " 元，超出预算 " + money(budget)
                    + " 元；请填写超支说明后确认。");
        }
        if (expenseMapper.transition(expenseId, Expense.STATUS_PENDING, Expense.STATUS_APPROVED,
                null, accountId, null, false, note) != 1) {
            throw conflict("EXPENSE_STATE_CONFLICT", "支出状态不允许确认", "该支出状态已变化，请刷新后重试。");
        }
        auditService.record(accountId, "EXPENSE", "EXPENSE_APPROVED", "expense:" + expenseId, note,
            Map.of("activityId", activityId, "amount", money(expense.getAmount()),
                "approvedTotal", money(projected), "overBudget", projected.compareTo(budget) > 0));
        notifyIntent(expense.getSubmittedBy() == null ? expense.getCreatedBy() : expense.getSubmittedBy(),
            "EXPENSE_APPROVED", activityId, "你登记的支出已确认。", accountId,
            "expense:" + expenseId + ":approved");
        return toItem(expenseMapper.findById(expenseId));
    }

    /** 驳回：仅组织负责人，需说明原因；驳回后可修改重提。 */
    @Transactional
    public ExpenseItem reject(
        Long accountId, Long orgId, Long activityId, Long expenseId, String reason, Long version) {
        requireOwnerContext(accountId, orgId, activityId);
        requireVersion(version);
        Expense expense = requireExpense(orgId, activityId, expenseId);
        if (!Expense.STATUS_PENDING.equals(expense.getStatus())) {
            throw conflict("EXPENSE_STATE_CONFLICT", "支出状态不允许驳回", "仅待确认的支出可以驳回。");
        }
        if (!expense.getVersion().equals(version) || expenseMapper.transition(
                expenseId, Expense.STATUS_PENDING, Expense.STATUS_REJECTED, null, accountId, reason, false, null) != 1) {
            throw conflict("EDIT_CONFLICT", "支出已被他人修改", "该支出状态或版本已变化，请刷新后重试。");
        }
        auditService.record(accountId, "EXPENSE", "EXPENSE_REJECTED", "expense:" + expenseId, reason,
            Map.of("activityId", activityId));
        notifyIntent(expense.getSubmittedBy() == null ? expense.getCreatedBy() : expense.getSubmittedBy(),
            "EXPENSE_REJECTED", activityId, "你登记的支出被驳回：" + (reason == null ? "" : reason), accountId,
            "expense:" + expenseId + ":rejected");
        return toItem(expenseMapper.findById(expenseId));
    }

    /**
     * 作废已确认支出：作废后不再计入统计；如需更正，先作废再建立关联原记录的更正登记（P5.2）。
     */
    @Transactional
    public ExpenseItem voidExpense(
        Long accountId, Long orgId, Long activityId, Long expenseId, String reason, Long version) {
        requireOwnerContext(accountId, orgId, activityId);
        requireVersion(version);
        Expense expense = requireExpense(orgId, activityId, expenseId);
        if (!Expense.STATUS_APPROVED.equals(expense.getStatus())) {
            throw conflict("EXPENSE_STATE_CONFLICT", "支出状态不允许作废", "仅已确认的支出可以作废。");
        }
        if (!expense.getVersion().equals(version) || expenseMapper.voidApproved(expenseId, accountId, reason) != 1) {
            throw conflict("EDIT_CONFLICT", "支出已被他人修改", "该支出状态或版本已变化，请刷新后重试。");
        }
        auditService.record(accountId, "EXPENSE", "EXPENSE_VOIDED", "expense:" + expenseId, reason,
            Map.of("activityId", activityId, "amount", money(expense.getAmount())));
        return toItem(expenseMapper.findById(expenseId));
    }

    /** 更正登记：原记录必须是已作废或已确认；新记录关联原记录，重新提交与确认后只计一次。 */
    @Transactional
    public ExpenseItem createCorrection(
        Long accountId, Long orgId, Long activityId, Long originalId, String idempotencyKey, Expense draft) {
        Context ctx = requireRegisterContext(accountId, orgId, activityId);
        Expense original = requireExpense(orgId, activityId, originalId);
        if (Expense.STATUS_DRAFT.equals(original.getStatus()) || Expense.STATUS_PENDING.equals(original.getStatus())) {
            throw conflict("EXPENSE_STATE_CONFLICT", "原纪录状态不允许更正",
                "更正针对已确认或已作废的支出；草稿与待确认请直接修改。");
        }
        String requestKey = requireIdempotencyKey(idempotencyKey);
        validateDraft(draft);
        String fingerprint = fingerprintOf(draft, originalId);
        Expense existing = expenseMapper.findByRequestKey(orgId, requestKey);
        if (existing != null) {
            return replayResult(existing, fingerprint);
        }
        draft.setOrgId(orgId);
        draft.setActivityId(activityId);
        draft.setStatus(Expense.STATUS_DRAFT);
        draft.setCreatedBy(accountId);
        draft.setCorrectsExpenseId(originalId);
        draft.setRequestKey(requestKey);
        draft.setRequestFingerprint(fingerprint);
        expenseMapper.insert(draft);
        auditService.record(accountId, "EXPENSE", "EXPENSE_CORRECTION_CREATED", "expense:" + draft.getId(),
            null, Map.of("activityId", activityId, "corrects", originalId));
        return toItem(expenseMapper.findById(draft.getId()));
    }

    // ---------- 内部 ----------

    /** 可见性：组织有效成员 + 活动对该成员可见；否则 404（与不存在同响应）。 */
    private Context loadVisible(Long accountId, Long orgId, Long activityId) {
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        Activity activity = actor == null ? null : activityMapper.findById(activityId);
        if (actor == null || activity == null || !activity.getOrgId().equals(orgId)
            || !activity.isVisibleTo(actor, accountId)) {
            throw notFound();
        }
        boolean orgOwner = OrganizationMember.ROLE_OWNER.equals(actor.getRole());
        boolean activityOwner = actor.getId().equals(activity.getOwnerMemberId());
        return new Context(actor, activity, orgOwner, activityOwner);
    }

    /** 读取与登记：组织 → 活动共享锁，锁内重读身份与活动（F04）。 */
    private Context requireRegisterContext(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganizationShared(orgId);
        activityMapper.lockActivityShared(activityId);
        Context ctx = loadVisible(accountId, orgId, activityId);
        if (!ctx.canRegister()) {
            throw notFound();
        }
        // 登记费用属于筹备期动作；调整期待确认期间费用只读（计划书 6.1）
        requirePreparationWritable(ctx.activity());
        return ctx;
    }

    /** 仅查看：不加锁，权限同为组织负责人或活动负责人。 */
    private Context requireManageContext(Long accountId, Long orgId, Long activityId) {
        Context ctx = loadVisible(accountId, orgId, activityId);
        if (!ctx.canRegister()) {
            throw notFound();
        }
        return ctx;
    }

    /**
     * 确认与作废：组织 → 活动排他锁（锁内求已确认合计），仅组织负责人。
     * 活动负责人非组织负责人时返回 403，区分“看不到”与“看得到但不能确认”。
     */
    private Context requireOwnerContext(Long accountId, Long orgId, Long activityId) {
        organizationMapper.lockOrganizationShared(orgId);
        activityMapper.lockActivity(activityId);
        Context ctx = loadVisible(accountId, orgId, activityId);
        if (!ctx.canRegister()) {
            throw notFound();
        }
        if (!ctx.orgOwner()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问",
                "仅组织负责人可以确认或作废支出。");
        }
        requirePreparationWritable(ctx.activity());
        return ctx;
    }

    /** 调整期待确认期间费用只读；已取消活动仍允许处理遗留费用（计划书 6.3）。 */
    private void requirePreparationWritable(Activity activity) {
        if (activity.isPendingReconfirmation()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动安排调整中",
                "活动安排调整中，期间费用只读；重新确认后再处理。");
        }
        if (!activity.isExpenseWritable()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_STATE_CONFLICT", "活动尚未进入筹备",
                Activity.STATUS_ARCHIVED.equals(activity.getStatus())
                    ? "活动已归档，费用只读；如需更正请先由组织负责人重新打开活动。"
                    : "费用只能在活动确认后的筹备、执行、复盘或取消遗留处理阶段登记。");
        }
    }

    private void validateDraft(Expense draft) {
        if (draft.getAmount() == null || draft.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw fieldError("amount", "支出金额必须大于零");
        }
        if (draft.getAmount().scale() > 2) {
            throw fieldError("amount", "金额最多两位小数");
        }
        if (draft.getIncurredOn() == null) {
            throw fieldError("incurredOn", "请填写费用发生日期");
        }
        if (!Expense.CATEGORIES.contains(draft.getCategory())) {
            throw fieldError("category", "请选择有效的费用分类");
        }
    }

    private void requireVersion(Long version) {
        if (version == null) {
            throw fieldError("version", "请携带当前版本号（version）后再提交");
        }
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "缺少防重键",
                "登记支出必须携带 Idempotency-Key 请求头（1～64 字符）。");
        }
        return idempotencyKey.trim();
    }

    /** 防重指纹：登记输入的规范化摘要；更正登记把原记录 id 一并纳入。 */
    private String fingerprintOf(Expense draft, Long correctsId) {
        String canonical = String.join("\u001f",
            String.valueOf(draft.getPurpose()),
            draft.getAmount() == null ? "null" : draft.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            String.valueOf(draft.getIncurredOn()),
            String.valueOf(draft.getCategory()),
            String.valueOf(blankToNull(draft.getHandledNote())),
            String.valueOf(blankToNull(draft.getNoReceiptNote())),
            String.valueOf(correctsId));
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

    private ExpenseItem replayResult(Expense existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint())) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "重复的防重键",
                "该 Idempotency-Key 已用于登记内容不同的支出，请使用新的键重试。");
        }
        return toItem(existing);
    }

    private Expense requireExpense(Long orgId, Long activityId, Long expenseId) {
        Expense expense = expenseMapper.findById(expenseId);
        if (expense == null || !expense.getOrgId().equals(orgId)
            || !expense.getActivityId().equals(activityId)) {
            throw notFound();
        }
        return expense;
    }

    private String requireActivityName(Long activityId) {
        Activity activity = activityMapper.findById(activityId);
        return activity == null ? "（活动已不可见）" : activity.getName();
    }

    private void notifyIntent(Long recipient, String type, Long activityId, String payload, String sourceKey) {
        notifyIntent(recipient, type, activityId, payload, null, sourceKey);
    }

    /**
     * 站内提示来源与业务同事务落库（计划书 6.7）；操作者本人不给自己发提示；
     * {@code sourceKey} 标识来源操作，与接收账号、类型一起唯一，重放不重复（P7.3）。
     */
    private void notifyIntent(Long recipient, String type, Long activityId, String payload,
        Long actorAccountId, String sourceKey) {
        if (recipient == null || recipient.equals(actorAccountId)) {
            return;
        }
        notificationMapper.insert(recipient, type, activityId,
            payload.length() > 500 ? payload.substring(0, 500) : payload, sourceKey);
    }

    private ExpenseItem toItem(Expense expense) {
        return new ExpenseItem(
            expense.getId(), expense.getActivityId(), expense.getPurpose(), money(expense.getAmount()),
            expense.getIncurredOn(), expense.getCategory(), expense.getHandledNote(), expense.getNoReceiptNote(),
            expense.getStatus(), expense.getSubmittedBy(), expense.getSubmittedAt(), expense.getDecidedBy(),
            expense.getDecidedAt(), expense.getRejectReason(), expense.getOverBudgetNote(),
            expense.getVoidReason(), expense.getVoidedAt(), expense.getCorrectsExpenseId(),
            String.valueOf(attachmentMapper.countActiveByExpense(expense.getId())),
            expense.getVersion());
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "支出或活动不存在、或无权访问。");
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效", "请求字段校验失败，请修改后重试。",
            List.of(new com.example.activity.common.FieldErrorItem(field, message)));
    }
}
