package com.example.activity.modules.notification;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内提示端点（P7.3）：本人提示列表、标记已读与全部已读。
 * 列表与详情跳转都重新校验权限；目标详情仍走各业务端点，本模块不代读内部数据。
 */
@RestController
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public record NotificationResponse(
        String notificationId, String type, String payload, java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime readAt, String activityId, String activityName, String activityPath) {
    }

    public record NotificationsResponse(
        List<NotificationResponse> items, int page, int pageSize, long total, long unread) {
    }

    @GetMapping("/api/me/notifications")
    public ResponseEntity<NotificationsResponse> listMine(
        @AuthenticationPrincipal AccountUserDetails principal,
        @RequestParam(name = "page", defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
        @RequestParam(name = "pageSize", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条") @Max(value = 100, message = "每页最多 100 条") int pageSize) {
        NotificationService.NotificationPage result =
            notificationService.listMine(principal.getAccountId(), page, pageSize);
        List<NotificationResponse> items = result.items().stream()
            .map(item -> new NotificationResponse(String.valueOf(item.notificationId()), item.type(),
                item.payload(), item.createdAt(), item.readAt(), item.activityId(), item.activityName(), item.activityPath()))
            .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new NotificationsResponse(items, result.page(), result.pageSize(), result.total(),
                result.unread()));
    }

    @PostMapping("/api/me/notifications/{notificationId}/read")
    public ResponseEntity<Void> markRead(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("notificationId") Long notificationId) {
        notificationService.markRead(principal.getAccountId(), notificationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/me/notifications/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal AccountUserDetails principal) {
        notificationService.markAllRead(principal.getAccountId());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
