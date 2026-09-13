package com.example.activity.modules.feedback;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈端点（P6.3）：提交／修改本人反馈、查看汇总与明细。
 * 资格依据与当前身份校验在 Service 内完成；原始反馈只对管理者与本人可见。
 */
@RestController
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    public record FeedbackResponse(
        String feedbackId, Long accountId, String accountUsername, Integer rating, String comment,
        java.time.OffsetDateTime createdAt, java.time.OffsetDateTime updatedAt) {
    }

    public record FeedbackSummaryResponse(
        long count, String averageRating, List<FeedbackResponse> items, boolean mineSubmitted,
        Integer myRating, String myComment, boolean canSubmit, String submitBlockedReason) {
    }

    public record SubmitFeedbackRequest(
        @NotNull(message = "请给出 1～5 分评分")
        @Min(value = 1, message = "评分最低 1 分")
        @Max(value = 5, message = "评分最高 5 分") Integer rating,
        @Size(max = 2000, message = "文字建议不超过 2000 字符") String comment) {
    }

    private FeedbackResponse toResponse(FeedbackService.FeedbackItem item) {
        return new FeedbackResponse(String.valueOf(item.feedbackId()), item.accountId(),
            item.accountUsername(), item.rating(), item.comment(), item.createdAt(), item.updatedAt());
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/feedback")
    public ResponseEntity<FeedbackSummaryResponse> summary(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        FeedbackService.FeedbackSummary summary =
            feedbackService.summary(principal.getAccountId(), orgId, activityId);
        List<FeedbackResponse> items = summary.items().stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new FeedbackSummaryResponse(summary.count(), summary.averageRating(), items,
                summary.mineSubmitted(), summary.myRating(), summary.myComment(),
                summary.canSubmit(), summary.submitBlockedReason()));
    }

    /** 提交或修改本人反馈：每账号每活动一份，归档前允许修改。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/feedback")
    public ResponseEntity<FeedbackResponse> submit(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody SubmitFeedbackRequest request) {
        FeedbackService.FeedbackItem saved = feedbackService.submit(
            principal.getAccountId(), orgId, activityId, request.rating(),
            request.comment() == null ? null : request.comment().trim());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(saved));
    }
}
