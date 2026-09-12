package com.example.activity.modules.organization;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 组织邀请端点（P1.3）：创建／列表／撤销仅负责人；接受对任意登录账号开放。 */
@RestController
public class OrganizationInvitationController {

    private final OrganizationInvitationService invitationService;

    public OrganizationInvitationController(OrganizationInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    public record CreateInvitationRequest(
        @Min(value = 1, message = "有效期至少 1 天") @Max(value = 30, message = "有效期最多 30 天") Integer expiresInDays,
        @Min(value = 1, message = "使用次数至少 1 次") @Max(value = 100, message = "使用次数最多 100 次") Integer maxUses) {
    }

    public record CreatedResponse(
        String invitationId, String code, java.time.OffsetDateTime expiresAt, int maxUses) {
    }

    public record InvitationsResponse(List<InvitationItemResponse> items) {
    }

    public record InvitationItemResponse(
        String invitationId, Long createdBy, java.time.OffsetDateTime createdAt,
        java.time.OffsetDateTime expiresAt, int maxUses, int usedCount, String status) {
    }

    public record AcceptInvitationRequest(@NotBlank(message = "邀请码不能为空") String code) {
    }

    public record AcceptedResponse(String orgId, String name, String role) {
    }

    @PostMapping("/api/organizations/{orgId}/invitations")
    public ResponseEntity<CreatedResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody CreateInvitationRequest request) {
        int expiresInDays = request.expiresInDays() == null ? 7 : request.expiresInDays();
        int maxUses = request.maxUses() == null ? 10 : request.maxUses();
        OrganizationInvitationService.InvitationCreated created =
            invitationService.create(principal.getAccountId(), orgId, expiresInDays, maxUses);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new CreatedResponse(String.valueOf(created.invitationId()), created.code(),
                created.expiresAt(), created.maxUses()));
    }

    @GetMapping("/api/organizations/{orgId}/invitations")
    public ResponseEntity<InvitationsResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId) {
        List<InvitationItemResponse> items = invitationService.list(principal.getAccountId(), orgId).stream()
            .map(item -> new InvitationItemResponse(String.valueOf(item.invitationId()), item.createdBy(),
                item.createdAt(), item.expiresAt(), item.maxUses(), item.usedCount(), item.status()))
            .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new InvitationsResponse(items));
    }

    @PostMapping("/api/organizations/{orgId}/invitations/{invitationId}/revoke")
    public ResponseEntity<Void> revoke(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("invitationId") Long invitationId) {
        invitationService.revoke(principal.getAccountId(), orgId, invitationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/invitations/accept")
    public ResponseEntity<AcceptedResponse> accept(
        @AuthenticationPrincipal AccountUserDetails principal,
        @Valid @RequestBody AcceptInvitationRequest request) {
        OrganizationInvitationService.Accepted accepted =
            invitationService.accept(principal.getAccountId(), request.code());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new AcceptedResponse(String.valueOf(accepted.orgId()), accepted.name(), accepted.role()));
    }
}
