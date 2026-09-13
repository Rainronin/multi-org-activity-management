package com.example.activity.modules.activity;

import com.example.activity.security.AccountUserDetails;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开活动端点（P4.1）：匿名可读的已发布基础信息，以及登录后的本人反馈入口（P9.1）。
 * 报名、取消与本人结果仍走登录端点，公开响应不含任何内部字段。
 */
@RestController
public class PublicActivityController {

    private final PublicActivityService publicActivityService;
    private final com.example.activity.modules.feedback.FeedbackService feedbackService;

    public PublicActivityController(
        PublicActivityService publicActivityService,
        com.example.activity.modules.feedback.FeedbackService feedbackService) {
        this.publicActivityService = publicActivityService;
        this.feedbackService = feedbackService;
    }

    public record PublicActivityResponse(
        String id, String organizationName, String name, String description, String location,
        java.time.OffsetDateTime startTime, java.time.OffsetDateTime endTime, String registrationScope,
        Integer capacity, java.time.OffsetDateTime registrationStart,
        java.time.OffsetDateTime registrationEnd, boolean registrationOpen,
        java.time.OffsetDateTime updatedAt) {
    }

    public record PublicActivitiesResponse(List<PublicActivityResponse> items, int page, int pageSize, long total) {
    }

    private PublicActivityResponse toResponse(PublicActivityService.PublicActivityItem item) {
        return new PublicActivityResponse(item.id(), item.organizationName(), item.name(), item.description(),
            item.location(), item.startTime(), item.endTime(), item.registrationScope(), item.capacity(),
            item.registrationStart(), item.registrationEnd(), item.registrationOpen(), item.updatedAt());
    }

    @GetMapping("/api/public/activities")
    public ResponseEntity<PublicActivitiesResponse> list(        @AuthenticationPrincipal AccountUserDetails principal,
        @RequestParam(name = "page", defaultValue = "1") int page,
        @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        PublicActivityService.PublicActivityPage result = publicActivityService.list(page, pageSize);
        List<PublicActivityResponse> items = result.items().stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new PublicActivitiesResponse(items, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/api/public/activities/{activityId}")
    public ResponseEntity<PublicActivityResponse> detail(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("activityId") Long activityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(publicActivityService.get(activityId)));
    }

    // ---------- 外部参与者的本人反馈（P9.1）：公开活动参与者在复盘中可反馈，不必加入组织 ----------

    public record PublicFeedbackResponse(
        String feedbackId, Integer rating, String comment,
        java.time.OffsetDateTime createdAt, java.time.OffsetDateTime updatedAt) {
    }

    public record PublicFeedbackSummaryResponse(
        long count, String averageRating, boolean mineSubmitted, Integer myRating, String myComment,
        boolean canSubmit, String submitBlockedReason) {
    }

    public record SubmitPublicFeedbackRequest(
        @jakarta.validation.constraints.NotNull(message = "请给出 1～5 分评分")
        @jakarta.validation.constraints.Min(value = 1, message = "评分最低 1 分")
        @jakarta.validation.constraints.Max(value = 5, message = "评分最高 5 分") Integer rating,
        @jakarta.validation.constraints.Size(max = 2000, message = "文字建议不超过 2000 字符") String comment) {
    }

    /** 只返回汇总与本人一份，不向外部参与者暴露其他参与者的评分与文字。 */
    @GetMapping("/api/public/activities/{activityId}/feedback")
    public ResponseEntity<PublicFeedbackSummaryResponse> feedbackSummary(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("activityId") Long activityId) {
        requireLogin(principal);
        Long orgId = publicActivityService.requirePublished(activityId).getOrgId();
        com.example.activity.modules.feedback.FeedbackService.FeedbackSummary summary =
            feedbackService.summary(principal.getAccountId(), orgId, activityId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new PublicFeedbackSummaryResponse(summary.count(), summary.averageRating(),
                summary.mineSubmitted(), summary.myRating(), summary.myComment(),
                summary.canSubmit(), summary.submitBlockedReason()));
    }

    /** 提交或修改本人反馈：每账号每活动一份，需有资格依据（报名通过等）且活动处于复盘期。 */
    @org.springframework.web.bind.annotation.PostMapping("/api/public/activities/{activityId}/feedback")
    public ResponseEntity<PublicFeedbackResponse> submitFeedback(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("activityId") Long activityId,
        @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
        SubmitPublicFeedbackRequest request) {
        requireLogin(principal);
        Long orgId = publicActivityService.requirePublished(activityId).getOrgId();
        com.example.activity.modules.feedback.FeedbackService.FeedbackItem saved =
            feedbackService.submit(principal.getAccountId(), orgId, activityId, request.rating(),
                request.comment() == null ? null : request.comment().trim());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new PublicFeedbackResponse(String.valueOf(saved.feedbackId()), saved.rating(),
                saved.comment(), saved.createdAt(), saved.updatedAt()));
    }

    private void requireLogin(AccountUserDetails principal) {
        if (principal == null) {
            throw new com.example.activity.common.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "AUTH_REQUIRED", "未登录或会话已失效", "请先登录后再查看或提交反馈。");
        }
    }
}
