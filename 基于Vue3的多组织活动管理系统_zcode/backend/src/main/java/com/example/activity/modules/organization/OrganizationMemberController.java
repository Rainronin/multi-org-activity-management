package com.example.activity.modules.organization;

import com.example.activity.security.AccountUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 组织成员与负责人端点（P1.3，合同见 项目文档/接口说明.md）。
 * 授权与最后负责人保护在 Service 内组织行排他锁下完成。
 */
@RestController
public class OrganizationMemberController {

    private final OrganizationMemberService memberService;

    public OrganizationMemberController(OrganizationMemberService memberService) {
        this.memberService = memberService;
    }

    public record MembersResponse(List<MemberItemResponse> items) {
    }

    public record MemberItemResponse(String memberId, String username, String role, java.time.OffsetDateTime joinedAt) {
    }

    public record RoleChangeResponse(String memberId, String role) {
    }

    public record TransferOwnershipRequest(@NotNull Long toMemberId) {
    }

    @GetMapping("/api/organizations/{orgId}/members")
    public ResponseEntity<MembersResponse> list(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId) {
        List<MemberItemResponse> items = memberService.listMembers(principal.getAccountId(), orgId).stream()
            .map(item -> new MemberItemResponse(
                String.valueOf(item.memberId()), item.username(), item.role(), item.joinedAt()))
            .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MembersResponse(items));
    }

    @DeleteMapping("/api/organizations/{orgId}/members/me")
    public ResponseEntity<Void> leave(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId) {
        memberService.leave(principal.getAccountId(), orgId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/api/organizations/{orgId}/members/{memberId}")
    public ResponseEntity<Void> remove(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("memberId") Long memberId) {
        memberService.remove(principal.getAccountId(), orgId, memberId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/organizations/{orgId}/members/{memberId}/promote-owner")
    public ResponseEntity<RoleChangeResponse> promote(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("memberId") Long memberId) {
        OrganizationMemberService.RoleChangeResult result =
            memberService.promoteOwner(principal.getAccountId(), orgId, memberId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new RoleChangeResponse(String.valueOf(result.memberId()), result.role()));
    }

    @PostMapping("/api/organizations/{orgId}/members/{memberId}/revoke-owner")
    public ResponseEntity<RoleChangeResponse> revoke(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @PathVariable("memberId") Long memberId) {
        OrganizationMemberService.RoleChangeResult result =
            memberService.revokeOwner(principal.getAccountId(), orgId, memberId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new RoleChangeResponse(String.valueOf(result.memberId()), result.role()));
    }

    @PostMapping("/api/organizations/{orgId}/ownership/transfer")
    public ResponseEntity<RoleChangeResponse> transfer(
        @AuthenticationPrincipal AccountUserDetails principal,
        @PathVariable("orgId") Long orgId,
        @Valid @RequestBody TransferOwnershipRequest request) {
        OrganizationMemberService.RoleChangeResult result =
            memberService.transferOwnership(principal.getAccountId(), orgId, request.toMemberId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new RoleChangeResponse(String.valueOf(result.memberId()), result.role()));
    }
}
