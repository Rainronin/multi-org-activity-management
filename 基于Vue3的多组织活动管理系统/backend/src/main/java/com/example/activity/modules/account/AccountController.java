package com.example.activity.modules.account;

import com.example.activity.security.AccessMapper;
import com.example.activity.security.AccountUserDetails;
import com.example.activity.security.AuthVersionFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号端点：注册、当前身份、恢复、重签与改密（合同见 项目文档/接口说明.md）。
 * 登录与退出由 Spring Security 过滤器处理，不在此重复声明。
 */
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private final AccountService accountService;
    private final AccessMapper accessMapper;

    public AccountController(AccountService accountService, AccessMapper accessMapper) {
        this.accountService = accountService;
        this.accessMapper = accessMapper;
    }

    public record RegisteredResponse(String id, String username, String recoveryCode) {
    }

    public record MeResponse(String id, String username) {
    }

    public record RecoveryResponse(String recoveryCode) {
    }

    @PostMapping("/register")
    public ResponseEntity<RegisteredResponse> register(@Valid @RequestBody RegisterRequest request) {
        AccountService.RegistrationResult result = accountService.register(request.username(), request.password());
        RegisteredResponse body =
            new RegisteredResponse(String.valueOf(result.accountId()), result.username(), result.recoveryCode());
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(body);
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AccountUserDetails principal) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new MeResponse(String.valueOf(principal.getAccountId()), principal.getUsername()));
    }

    @PostMapping("/recover")
    public ResponseEntity<RecoveryResponse> recover(@Valid @RequestBody RecoverRequest request) {
        AccountService.RecoveryResult result =
            accountService.recover(request.username(), request.recoveryCode(), request.newPassword());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new RecoveryResponse(result.recoveryCode()));
    }

    @PostMapping("/recovery/reissue")
    public ResponseEntity<RecoveryResponse> reissue(
        @AuthenticationPrincipal AccountUserDetails principal,
        @Valid @RequestBody ReissueRequest request) {
        AccountService.RecoveryResult result = accountService.reissueRecoveryCode(principal.getAccountId(), request.currentPassword());
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new RecoveryResponse(result.recoveryCode()));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
        @AuthenticationPrincipal AccountUserDetails principal,
        @Valid @RequestBody ChangePasswordRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse) {
        long newVersion = accountService.changePassword(principal.getAccountId(), request.currentPassword(), request.newPassword());
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.setAttribute(AuthVersionFilter.SESSION_KEY, newVersion);
        }
        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .build();
    }
}
