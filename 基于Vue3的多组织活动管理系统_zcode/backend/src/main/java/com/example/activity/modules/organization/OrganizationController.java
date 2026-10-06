package com.example.activity.modules.organization;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 组织端点（P1.2）：创建、本人目录、有效成员视角的组织详情。
 * 权限按当前数据库状态逐请求判断；非成员访问详情一律 404。
 */
@RestController
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    public record CreatedResponse(String id, String name, String description, String role, OffsetDateTime joinedAt) {
    }

    public record DirectoryResponse(List<DirectoryItem> items) {
    }

    public record DirectoryItem(String id, String name, String role, OffsetDateTime joinedAt) {
    }

    public record DetailResponse(
        String id, String name, String description, String role, int memberCount, OffsetDateTime createdAt) {
    }

    @PostMapping("/api/organizations")
    public ResponseEntity<CreatedResponse> create(
        @AuthenticationPrincipal AccountUserDetails principal,
        @RequestHeader(name = "Idempotency-Key", required = false) String requestKey,
        @Valid @RequestBody CreateOrganizationRequest request) {
        String description = request.description() == null || request.description().isBlank()
            ? null
            : request.description().trim();
        OrganizationService.OrganizationCreated created =
            organizationService.create(principal.getAccountId(), request.name().trim(), description, requestKey);
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(new CreatedResponse(String.valueOf(created.orgId()), created.name(),
                created.description(), created.role(), created.joinedAt()));
    }

    @GetMapping("/api/me/organizations")
    public ResponseEntity<DirectoryResponse> listMine(@AuthenticationPrincipal AccountUserDetails principal) {
        List<DirectoryItem> items = organizationService.listMine(principal.getAccountId()).stream()
            .map(item -> new DirectoryItem(String.valueOf(item.orgId()), item.name(), item.role(), item.joinedAt()))
            .toList();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new DirectoryResponse(items));
    }

    @GetMapping("/api/organizations/{orgId}")
    public ResponseEntity<DetailResponse> detail(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId) {
        OrganizationService.OrganizationDetail detail =
            organizationService.getDetailForAccount(principal.getAccountId(), orgId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new DetailResponse(String.valueOf(detail.orgId()), detail.name(), detail.description(),
                detail.role(), detail.memberCount(), detail.createdAt()));
    }
}
