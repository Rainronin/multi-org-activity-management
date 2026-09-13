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
 * 公开活动端点（P4.1）：匿名可读的已发布基础信息。
 * 报名、取消与本人结果仍走登录端点，公开响应不含任何内部字段。
 */
@RestController
public class PublicActivityController {

    private final PublicActivityService publicActivityService;

    public PublicActivityController(PublicActivityService publicActivityService) {
        this.publicActivityService = publicActivityService;
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
    public ResponseEntity<PublicActivitiesResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
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
}
