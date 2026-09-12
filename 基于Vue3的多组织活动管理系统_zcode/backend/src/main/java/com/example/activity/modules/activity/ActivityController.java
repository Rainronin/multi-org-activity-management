package com.example.activity.modules.activity;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        String status, OffsetDateTime startTime, OffsetDateTime endTime, String location,
        String budget, String registrationScope, Integer capacity, OffsetDateTime registrationStart,
        OffsetDateTime registrationEnd, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
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
        OffsetDateTime registrationEnd) {
    }

    @PostMapping("/api/organizations/{orgId}/activities")
    public ResponseEntity<ActivityResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
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
        ActivityService.ActivityItem created =
            activityService.create(principal.getAccountId(), principal.getUsername(), orgId, draft);
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
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(activityService.updateDraft(principal.getAccountId(), orgId, activityId, patch)));
    }

    private ActivityResponse toResponse(ActivityService.ActivityItem item) {
        return new ActivityResponse(
            String.valueOf(item.activityId()), item.name(), null, item.ownerMemberId(), item.ownerUsername(),
            item.status(), item.startTime(), item.endTime(), item.location(), item.budget(),
            item.registrationScope(), item.capacity(), item.registrationStart(), item.registrationEnd(),
            item.createdAt(), item.updatedAt());
    }
}
