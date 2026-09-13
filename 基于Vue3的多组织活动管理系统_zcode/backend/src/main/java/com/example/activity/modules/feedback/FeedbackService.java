package com.example.activity.modules.feedback;

import com.example.activity.common.ApiException;
import com.example.activity.common.audit.AuditService;
import com.example.activity.modules.activity.Activity;
import com.example.activity.modules.activity.ActivityMapper;
import com.example.activity.modules.organization.OrganizationMember;
import com.example.activity.modules.organization.OrganizationMemberMapper;
import com.example.activity.modules.organization.OrganizationMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 反馈（P6.3）：资格依据在执行开始／结束时固定，再校验当前身份；每账号每活动一份；
 * 首次进入复盘后开放，首次归档后永久关闭，取消活动保留历史但不新增或修改。
 *
 * <p>原始反馈仅组织负责人、活动负责人及当前仍有资格的本人可见。
 */
@Service
public class FeedbackService {

    private final FeedbackMapper feedbackMapper;
    private final ActivityMapper activityMapper;
    private final OrganizationMapper organizationMapper;
    private final OrganizationMemberMapper memberMapper;
    private final com.example.activity.modules.registration.RegistrationMapper registrationMapper;
    private final com.example.activity.modules.account.AccountMapper accountMapper;
    private final AuditService auditService;

    public FeedbackService(
        FeedbackMapper feedbackMapper,
        ActivityMapper activityMapper,
        OrganizationMapper organizationMapper,
        OrganizationMemberMapper memberMapper,
        com.example.activity.modules.registration.RegistrationMapper registrationMapper,
        com.example.activity.modules.account.AccountMapper accountMapper,
        AuditService auditService) {
        this.feedbackMapper = feedbackMapper;
        this.activityMapper = activityMapper;
        this.organizationMapper = organizationMapper;
        this.memberMapper = memberMapper;
        this.registrationMapper = registrationMapper;
        this.accountMapper = accountMapper;
        this.auditService = auditService;
    }

    public record FeedbackItem(
        Long feedbackId, Long accountId, String accountUsername, Integer rating, String comment,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, String source) {
    }

    /** 反馈汇总：份数与平均分；无反馈时平均分为 null，界面显示“暂无反馈”与“—”。 */
    public record FeedbackSummary(
        long count, String averageRating, List<FeedbackItem> items, boolean mineSubmitted,
        Integer myRating, String myComment, boolean canSubmit, String submitBlockedReason) {
    }

    private record Context(OrganizationMember actor, Activity activity, boolean orgOwner, boolean activityOwner) {
    }

    // ---------- 资格依据（由活动流程在执行开始／结束时写入） ----------

    /** 执行开始：固定当时的已批准报名者账号（公开活动的外部参与者按外部身份保留）。 */
    @Transactional
    public void snapshotApprovedRegistrants(Long activityId) {
        for (Long accountId : registrationMapper.listApprovedAccountIds(activityId)) {
            feedbackMapper.insertEligibility(activityId, accountId, Feedback.SOURCE_REGISTRATION_APPROVED);
        }
    }

    /** 执行结束：固定活动负责人与截至结束时未取消任务的承担者。 */
    @Transactional
    public void snapshotExecutionRoles(Long activityId, Long ownerAccountId, List<Long> assigneeAccountIds) {
        if (ownerAccountId != null) {
            feedbackMapper.insertEligibility(activityId, ownerAccountId, Feedback.SOURCE_ACTIVITY_OWNER);
        }
        for (Long accountId : assigneeAccountIds) {
            if (accountId != null) {
                feedbackMapper.insertEligibility(activityId, accountId, Feedback.SOURCE_TASK_ASSIGNEE);
            }
        }
    }

    // ---------- 提交与查看 ----------

    /**
     * 提交或修改本人反馈：活动必须处于复盘期（未归档、未取消），本人必须有资格依据且当前身份可校验。
     */
    @Transactional
    public FeedbackItem submit(
        Long accountId, Long orgId, Long activityId, Integer rating, String comment) {
        organizationMapper.lockOrganizationShared(orgId);
        activityMapper.lockActivityShared(activityId);
        Activity activity = requireVisibleActivity(accountId, orgId, activityId);
        if (Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
            throw conflict("FEEDBACK_CLOSED", "活动已取消", "活动取消后停止收集体验评分，已有反馈保留为历史。");
        }
        if (!activity.isFeedbackOpen()) {
            throw conflict("FEEDBACK_CLOSED", "当前不在反馈期",
                Activity.STATUS_ARCHIVED.equals(activity.getStatus())
                    ? "活动已归档，反馈不再接受新增或修改。"
                    : "活动首次进入复盘后才开放反馈。");
        }
        if (rating == null || rating < 1 || rating > 5) {
            throw fieldError("rating", "评分必须是 1～5 的整数");
        }
        requireEligible(accountId, orgId, activity);
        Feedback existing = feedbackMapper.findByActivityAndAccount(activityId, accountId);
        if (existing == null) {
            Feedback feedback = new Feedback();
            feedback.setOrgId(orgId);
            feedback.setActivityId(activityId);
            feedback.setAccountId(accountId);
            feedback.setRating(rating);
            feedback.setComment(trimToNull(comment));
            feedbackMapper.insert(feedback);
            auditService.record(accountId, "ACTIVITY", "FEEDBACK_SUBMITTED", "activity:" + activityId,
                null, Map.of("rating", rating));
            return toItem(feedbackMapper.findByActivityAndAccount(activityId, accountId));
        }
        feedbackMapper.update(existing.getId(), rating, trimToNull(comment));
        auditService.record(accountId, "ACTIVITY", "FEEDBACK_UPDATED", "activity:" + activityId,
            null, Map.of("rating", rating));
        return toItem(feedbackMapper.findByActivityAndAccount(activityId, accountId));
    }

    /** 反馈汇总与明细：管理者看全部，其他账号只看自己的一份。 */
    public FeedbackSummary summary(Long accountId, Long orgId, Long activityId) {
        Activity activity = requireVisibleActivity(accountId, orgId, activityId);
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        boolean manager = actor != null
            && (OrganizationMember.ROLE_OWNER.equals(actor.getRole())
                || actor.getId().equals(activity.getOwnerMemberId()));
        Feedback mine = feedbackMapper.findByActivityAndAccount(activityId, accountId);
        boolean eligible = feedbackMapper.countEligibility(activityId, accountId) > 0;
        boolean canSubmit = eligible && activity.isFeedbackOpen() && canVerifyIdentity(accountId, orgId, activity);
        String blockedReason = null;
        if (!canSubmit) {
            if (Activity.STATUS_CANCELLED.equals(activity.getStatus())) {
                blockedReason = "活动已取消，停止收集体验评分。";
            } else if (Activity.STATUS_ARCHIVED.equals(activity.getStatus())) {
                blockedReason = "活动已归档，反馈不再接受新增或修改。";
            } else if (!activity.isFeedbackOpen()) {
                blockedReason = "活动首次进入复盘后才开放反馈。";
            } else if (!eligible) {
                blockedReason = "本人不在本活动的反馈资格范围内（执行开始时的已批准报名者，或执行结束时的负责人与任务承担者）。";
            } else {
                blockedReason = "当前身份已不能校验（成员关系已失效）。";
            }
        }
        List<FeedbackItem> items;
        if (manager) {
            items = feedbackMapper.listByActivity(activityId).stream().map(this::toItem).toList();
        } else {
            items = mine == null ? List.of() : List.of(toItem(mine));
        }
        BigDecimal average = feedbackMapper.averageRating(activityId);
        return new FeedbackSummary(
            feedbackMapper.countByActivity(activityId),
            average == null ? null : average.setScale(1, RoundingMode.HALF_UP).toPlainString(),
            items, mine != null, mine == null ? null : mine.getRating(),
            mine == null ? null : mine.getComment(), canSubmit, blockedReason);
    }

    // ---------- 内部 ----------

    /** P6.4：总结来源摘要中的反馈投影（份数与平均分，固定两位小数）。 */
    public String summarySourcePart(Long activityId) {
        BigDecimal average = feedbackMapper.averageRating(activityId);
        return feedbackMapper.countByActivity(activityId) + ":"
            + (average == null ? "-" : average.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** P8.2：总结输入投影中的反馈要点：份数、平均分与有限条文字，不含反馈人身份。 */
    public record FeedbackDigest(long count, String averageRating, List<String> comments) {
    }

    /**
     * 反馈摘要：调用方已按活动可见性完成鉴权；只返回口径与文字要点，
     * 输出给模型的文字按条数上限截取，避免把完整反馈送入外部服务。
     */
    public FeedbackDigest digest(Long activityId, int maxComments, int maxCharsPerComment) {
        BigDecimal average = feedbackMapper.averageRating(activityId);
        List<String> comments = new java.util.ArrayList<>();
        for (Feedback feedback : feedbackMapper.listByActivity(activityId)) {
            if (comments.size() >= maxComments) {
                break;
            }
            String comment = feedback.getComment();
            if (comment == null || comment.isBlank()) {
                continue;
            }
            String trimmed = comment.trim();
            comments.add(trimmed.length() <= maxCharsPerComment
                ? trimmed : trimmed.substring(0, maxCharsPerComment) + "…");
        }
        return new FeedbackDigest(feedbackMapper.countByActivity(activityId),
            average == null ? "—" : average.setScale(1, RoundingMode.HALF_UP).toPlainString(), List.copyOf(comments));
    }

    private Activity requireVisibleActivity(Long accountId, Long orgId, Long activityId) {
        Activity activity = activityMapper.findById(activityId);
        OrganizationMember actor = memberMapper.findActive(orgId, accountId);
        if (activity == null || !activity.getOrgId().equals(orgId)) {
            throw notFound();
        }
        // 公开活动的外部报名者不是组织成员，凭反馈资格依据（报名者来源）可读本人反馈与汇总
        boolean eligible = feedbackMapper.countEligibility(activityId, accountId) > 0;
        if (actor == null && !(eligible && Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope()))) {
            throw notFound();
        }
        if (actor != null && !activity.isVisibleTo(actor, accountId)) {
            throw notFound();
        }
        return activity;
    }

    /**
     * 资格与身份校验：必须有固定下来的资格依据；内部成员须仍是有效成员，
     * 公开活动的外部报名者按外部身份允许（计划书 6.5）。
     */
    private void requireEligible(Long accountId, Long orgId, Activity activity) {
        if (feedbackMapper.countEligibility(activity.getId(), accountId) == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权提交反馈",
                "只有执行开始时的已批准报名者，以及执行结束时的活动负责人与任务承担者可以提交反馈。");
        }
        if (!canVerifyIdentity(accountId, orgId, activity)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权提交反馈",
                "本人已不是本组织有效成员，且该活动不是公开报名活动。");
        }
    }

    private boolean canVerifyIdentity(Long accountId, Long orgId, Activity activity) {
        if (memberMapper.findActive(orgId, accountId) != null) {
            return true;
        }
        return Activity.SCOPE_PUBLIC.equals(activity.getRegistrationScope());
    }

    private FeedbackItem toItem(Feedback feedback) {
        return new FeedbackItem(
            feedback.getId(), feedback.getAccountId(), resolveUsername(feedback.getAccountId()),
            feedback.getRating(), feedback.getComment(), feedback.getCreatedAt(), feedback.getUpdatedAt(), null);
    }

    private String resolveUsername(Long accountId) {
        // 外部参与者不是组织成员，账号名仍可安全展示（不含联系方式）
        return accountMapper.findUsernameById(accountId);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在", "活动不存在或无权访问。");
    }

    private ApiException conflict(String code, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, title, detail);
    }

    private ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "输入无效", "请求字段校验失败，请修改后重试。",
            List.of(new com.example.activity.common.FieldErrorItem(field, message)));
    }
}
