package com.example.activity.modules.activity;

import com.example.activity.common.ApiException;
import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动端点（P2.1，合同见 项目文档/接口说明.md）：
 * 草稿创建／列表／详情／编辑；权限与校验在 Service 内以组织共享锁校验。
 */
@RestController
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    public record ActivityResponse(
        String id, String name, String description, String ownerMemberId, String ownerUsername,
        String status, String proposal, String reviewNote, Long submittedBy, Long confirmedBy, String confirmType,
        OffsetDateTime confirmedAt, OffsetDateTime startTime, OffsetDateTime endTime, String location,
        String budget, String registrationScope, Integer capacity, OffsetDateTime registrationStart,
        OffsetDateTime registrationEnd, boolean registrationOpen, Long version,
        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ActivitiesResponse(List<ActivityResponse> items, int page, int pageSize, long total) {
    }

    public record CreateActivityRequest(
        @NotBlank(message = "活动主题不能为空") @Size(max = 128, message = "活动主题不超过 128 字符") String name,
        @NotNull(message = "活动负责人不能为空") Long ownerMemberId,
        @Size(max = 2000, message = "活动简介不超过 2000 字符") String description,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        @Size(max = 255, message = "活动地点不超过 255 字符") String location,
        @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "预算须为不小于零的金额，最多两位小数") String budget,
        @Pattern(regexp = "^(INTERNAL|PUBLIC)$", message = "报名范围仅支持 INTERNAL 或 PUBLIC") String registrationScope,
        @Min(value = 1, message = "名额必须为正整数") Integer capacity,
        OffsetDateTime registrationStart,
        OffsetDateTime registrationEnd) {
    }

    public record UpdateActivityRequest(
        @Size(max = 128, message = "活动主题不超过 128 字符") String name,
        @Size(max = 2000, message = "活动简介不超过 2000 字符") String description,
        Long ownerMemberId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        @Size(max = 255, message = "活动地点不超过 255 字符") String location,
        @Pattern(regexp = "^\\d+(\\.\\d{1,2})?$", message = "预算须为不小于零的金额，最多两位小数") String budget,
        @Pattern(regexp = "^(INTERNAL|PUBLIC)$", message = "报名范围仅支持 INTERNAL 或 PUBLIC") String registrationScope,
        @Min(value = 1, message = "名额必须为正整数") Integer capacity,
        OffsetDateTime registrationStart,
        OffsetDateTime registrationEnd,
        /** S08：期望版本；携带时做乐观锁，与实际版本不符返回 409 EDIT_CONFLICT。 */
        @Min(value = 1, message = "版本号必须为正整数") Long version) {
    }

    @PostMapping("/api/organizations/{orgId}/activities")
    public ResponseEntity<ActivityResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CreateActivityRequest request) {
        Activity draft = new Activity();
        draft.setName(request.name().trim());
        draft.setOwnerMemberId(request.ownerMemberId());
        draft.setDescription(request.description() == null || request.description().isBlank()
            ? null : request.description().trim());
        draft.setStartTime(request.startTime());
        draft.setEndTime(request.endTime());
        draft.setLocation(request.location() == null || request.location().isBlank()
            ? null : request.location().trim());
        draft.setBudget(request.budget() == null ? null : new java.math.BigDecimal(request.budget()));
        draft.setRegistrationScope(request.registrationScope());
        draft.setCapacity(request.capacity());
        draft.setRegistrationStart(request.registrationStart());
        draft.setRegistrationEnd(request.registrationEnd());
        ActivityService.ActivityItem created = activityService.create(
            principal.getAccountId(), principal.getUsername(), orgId,
            idempotencyKey == null ? null : idempotencyKey.trim(), draft);
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(toResponse(created));
    }

    @GetMapping("/api/organizations/{orgId}/activities")
    public ResponseEntity<ActivitiesResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @RequestParam(name = "page", defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
        @RequestParam(name = "pageSize", defaultValue = "20") @Min(value = 1, message = "每页至少 1 条") @Max(value = 100, message = "每页最多 100 条") int pageSize,
        @RequestParam(name = "query", required = false) String query,
        @RequestParam(name = "status", required = false) String status) {
        ActivityService.ActivityPage result =
            activityService.list(principal.getAccountId(), orgId, page, pageSize, query, status);
        List<ActivityResponse> items = result.items().stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ActivitiesResponse(items, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}")
    public ResponseEntity<ActivityResponse> detail(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.get(principal.getAccountId(), orgId, activityId)));
    }

    public record ProposalRequest(@NotBlank(message = "方案内容不能为空") @Size(max = 50000, message = "方案不超过 50000 字符") String content) {
    }

    public record ReviewRequest(@Size(max = 500, message = "审批说明不超过 500 字符") String note) {
    }

    public record RejectRequest(@NotBlank(message = "驳回须说明原因") @Size(max = 500, message = "驳回原因不超过 500 字符") String reason) {
    }

    public record WithdrawApprovalRequest(@Size(max = 500, message = "撤回原因不超过 500 字符") String reason) {
    }

    public record RegistrationOpenRequest(@NotNull(message = "请指定报名开关状态") Boolean open) {
    }

    /** 报名开关（S02／P4.2）：独立于报名时间窗，仅影响新申请。 */
    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/registration-open")
    public ResponseEntity<ActivityResponse> setRegistrationOpen(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody RegistrationOpenRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.setRegistrationOpen(
                principal.getAccountId(), orgId, activityId, request.open())));
    }

    @PutMapping("/api/organizations/{orgId}/activities/{activityId}/proposal")
    public ResponseEntity<ActivityResponse> saveProposal(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody ProposalRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.saveProposal(
                principal.getAccountId(), orgId, activityId, request.content())));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/submit")
    public ResponseEntity<ActivityResponse> submit(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.submit(principal.getAccountId(), orgId, activityId)));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/withdraw")
    public ResponseEntity<ActivityResponse> withdraw(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.withdraw(principal.getAccountId(), orgId, activityId)));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/approve")
    public ResponseEntity<ActivityResponse> approve(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody(required = false) ReviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.approve(
                principal.getAccountId(), orgId, activityId, request == null ? null : request.note())));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/reject")
    public ResponseEntity<ActivityResponse> reject(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody RejectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.reject(
                principal.getAccountId(), orgId, activityId, request.reason())));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/withdraw-approval")
    public ResponseEntity<ActivityResponse> withdrawApproval(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody(required = false) WithdrawApprovalRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.withdrawApproval(
                principal.getAccountId(), orgId, activityId, request == null ? null : request.reason())));
    }

    @DeleteMapping("/api/organizations/{orgId}/activities/{activityId}")
    public ResponseEntity<Void> deleteDraft(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId) {
        activityService.deleteDraft(principal.getAccountId(), orgId, activityId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PatchMapping("/api/organizations/{orgId}/activities/{activityId}")
    public ResponseEntity<ActivityResponse> updateDraft(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody UpdateActivityRequest request) {
        Activity patch = new Activity();
        patch.setId(activityId);
        patch.setOrgId(orgId);
        patch.setName(request.name() == null ? null : request.name().trim());
        patch.setDescription(request.description() == null || request.description().isBlank()
            ? null : request.description().trim());
        patch.setOwnerMemberId(request.ownerMemberId());
        patch.setStartTime(request.startTime());
        patch.setEndTime(request.endTime());
        patch.setLocation(request.location() == null || request.location().isBlank()
            ? null : request.location().trim());
        patch.setBudget(request.budget() == null ? null : new java.math.BigDecimal(request.budget()));
        patch.setRegistrationScope(request.registrationScope());
        patch.setCapacity(request.capacity());
        patch.setRegistrationStart(request.registrationStart());
        patch.setRegistrationEnd(request.registrationEnd());
        patch.setVersion(request.version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.updateDraft(principal.getAccountId(), orgId, activityId, patch)));
    }

    private ActivityResponse toResponse(ActivityService.ActivityItem item) {
        return new ActivityResponse(
            String.valueOf(item.activityId()), item.name(), item.description(), item.ownerMemberId(),
            item.ownerUsername(), item.status(), item.proposal(), item.reviewNote(),
            item.submittedBy(), item.confirmedBy(), item.confirmType(),
            item.confirmedAt(), item.startTime(), item.endTime(), item.location(), item.budget(),
            item.registrationScope(), item.capacity(), item.registrationStart(), item.registrationEnd(),
            item.registrationOpen(), item.version(), item.createdAt(), item.updatedAt());
    }
}
