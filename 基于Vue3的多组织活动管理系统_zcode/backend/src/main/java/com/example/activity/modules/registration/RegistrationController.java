package com.example.activity.modules.registration;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报名端点（P4）：申请、审核（名额并发控制）、取消、本人记录与管理名单。 */
@RestController
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    public record RegistrationResponse(
        String registrationId, String activityId, Long accountId, String accountUsername,
        String displayName, String note, String status, String cancelReason,
        OffsetDateTime decidedAt, OffsetDateTime createdAt) {
    }

    public record RegistrationsResponse(
        List<RegistrationResponse> items, int page, int pageSize, long total) {
    }

    public record ApplyRequest(
        @NotBlank(message = "展示姓名不能为空") @Size(max = 64, message = "姓名不超过 64 字符") String displayName,
        @Size(max = 500, message = "备注不超过 500 字符") String note) {
    }

    public record RejectRequest(@NotBlank(message = "驳回须说明原因") @Size(max = 500, message = "不超过 500 字符") String reason) {
    }

    public record CancelOrgRequest(@NotBlank(message = "取消须说明原因") @Size(max = 500, message = "不超过 500 字符") String reason) {
    }

    private RegistrationResponse toResponse(RegistrationService.RegistrationItem item) {
        return new RegistrationResponse(
            String.valueOf(item.registrationId()), String.valueOf(item.activityId()),
            item.accountId(), item.accountUsername(), item.displayName(), item.note(),
            item.status(), item.cancelReason(), item.decidedAt(), item.createdAt());
    }

    @PostMapping("/api/activities/{activityId}/registrations")
    public ResponseEntity<RegistrationResponse> apply(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("activityId") Long activityId,
        @Valid @RequestBody ApplyRequest request) {
        RegistrationService.RegistrationItem item = registrationService.apply(
            principal.getAccountId(), activityId, request.displayName(), request.note());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(toResponse(item));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/registrations/{registrationId}/approve")
    public ResponseEntity<RegistrationResponse> approve(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("registrationId") Long registrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(registrationService.approve(
                principal.getAccountId(), orgId, activityId, registrationId)));
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/registrations/{registrationId}/reject")
    public ResponseEntity<RegistrationResponse> reject(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("registrationId") Long registrationId,
        @Valid @RequestBody RejectRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(registrationService.reject(
                principal.getAccountId(), orgId, activityId, registrationId, request.reason())));
    }

    @PostMapping("/api/activities/{activityId}/registrations/cancel-self")
    public ResponseEntity<Void> cancelSelf(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("activityId") Long activityId) {
        registrationService.cancelSelf(principal.getAccountId(), activityId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/organizations/{orgId}/activities/{activityId}/registrations/{registrationId}/cancel-org")
    public ResponseEntity<RegistrationResponse> cancelOrg(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @PathVariable("registrationId") Long registrationId,
        @Valid @RequestBody CancelOrgRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(toResponse(registrationService.cancelOrg(
                principal.getAccountId(), orgId, activityId, registrationId, request.reason())));
    }

    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/registrations")
    public ResponseEntity<RegistrationsResponse> listForReview(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam(name = "page", defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") int page,
        @RequestParam(name = "pageSize", defaultValue = "20") @Min(value = 1, message = "每页至少 1 条") @Max(value = 100, message = "每页最多 100 条") int pageSize,
        @RequestParam(name = "status", required = false) String status) {
        RegistrationService.RegistrationPage result = registrationService.listForReview(
            principal.getAccountId(), orgId, activityId, page, pageSize, status);
        List<RegistrationResponse> items = result.items().stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new RegistrationsResponse(items, result.page(), result.pageSize(), result.total()));
    }

    @GetMapping("/api/me/registrations")
    public ResponseEntity<RegistrationsResponse> listMine(
        @AuthenticationPrincipal AccountUserDetails principal) {
        List<RegistrationResponse> items = registrationService.listMine(principal.getAccountId())
            .stream().map(this::toResponse).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new RegistrationsResponse(items, 1, 20, items.size()));
    }

    /**
     * 名单导出（P4.3）：权限与筛选同管理名单；CSV 文本按电子表格公式注入规则转义，
     * 防止用户填入的姓名／备注被表格软件当作公式执行。
     */
    @GetMapping("/api/organizations/{orgId}/activities/{activityId}/registrations/export")
    public ResponseEntity<byte[]> export(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("activityId") Long activityId,
        @RequestParam(name = "status", required = false) String status) {
        List<RegistrationService.RegistrationItem> rows =
            registrationService.exportForReview(principal.getAccountId(), orgId, activityId, status);
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("报名ID,账号,展示姓名,状态,报名时间,处理时间,取消原因,备注\r\n");
        for (RegistrationService.RegistrationItem item : rows) {
            csv.append(csvCell(String.valueOf(item.registrationId()))).append(',')
                .append(csvCell(item.accountUsername())).append(',')
                .append(csvCell(item.displayName())).append(',')
                .append(csvCell(statusLabel(item.status()))).append(',')
                .append(csvCell(formatTime(item.createdAt()))).append(',')
                .append(csvCell(formatTime(item.decidedAt()))).append(',')
                .append(csvCell(item.cancelReason())).append(',')
                .append(csvCell(item.note())).append("\r\n");
        }
        byte[] body = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "registrations-%d.csv".formatted(activityId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment()
                    .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .body(body);
    }

    private String formatTime(java.time.OffsetDateTime value) {
        return value == null ? "" : value.toString();
    }

    private String statusLabel(String status) {
        return switch (status) {
            case Registration.STATUS_PENDING -> "待审核";
            case Registration.STATUS_APPROVED -> "已通过";
            case Registration.STATUS_REJECTED -> "已驳回";
            case Registration.STATUS_CANCELLED_SELF -> "本人取消";
            case Registration.STATUS_CANCELLED_ORG -> "组织取消";
            default -> status;
        };
    }

    /** CSV 单元格：加引号转义，并把可能被表格软件当公式的起始字符前置单引号。 */
    private String csvCell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
