package com.example.activity.modules.account;

import com.example.activity.common.ApiException;
import com.example.activity.common.FieldErrorItem;
import com.example.activity.common.audit.AuditService;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号业务：注册、恢复、重签、改密。恢复码明文只在发放时返回一次；
 * 恢复在同一事务中完成改密、旧码消费、新码发放与认证版本递增（后端架构 4.1）。
 */
@Service
public class AccountService {

    public record RegistrationResult(Long accountId, String username, String recoveryCode) {
    }

    public record RecoveryResult(String recoveryCode) {
    }

    private static final String UNIFIED_RECOVERY_FAILURE = "账号或恢复码无效。";

    private final AccountMapper accountMapper;
    private final RecoveryCredentialMapper recoveryCredentialMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AccountService(
        AccountMapper accountMapper,
        RecoveryCredentialMapper recoveryCredentialMapper,
        PasswordEncoder passwordEncoder,
        AuditService auditService) {
        this.accountMapper = accountMapper;
        this.recoveryCredentialMapper = recoveryCredentialMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public RegistrationResult register(String username, String password) {
        Account account = new Account();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setStatus("ACTIVE");
        account.setAuthVersion(1L);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "账号已存在",
                "该用户名已被注册，请换一个。",
                List.of(new FieldErrorItem("username", "该用户名已被注册")));
        }

        String recoveryCode = RecoveryCodes.generate();
        insertActiveCredential(account.getId(), recoveryCode);
        auditService.record(account.getId(), "ACCOUNT", "ACCOUNT_REGISTERED", "account:" + account.getId(), null,
            java.util.Map.of("username", username));
        return new RegistrationResult(account.getId(), username, recoveryCode);
    }

    @Transactional
    public RecoveryResult recover(String username, String recoveryCode, String newPassword) {
        Account account = accountMapper.findByUsername(username);
        RecoveryCredential credential = account == null ? null : recoveryCredentialMapper.findActiveByAccountId(account.getId());
        if (credential == null || !RecoveryCodes.hash(RecoveryCodes.normalize(recoveryCode)).equals(credential.getCodeHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "恢复失败", UNIFIED_RECOVERY_FAILURE);
        }
        // 并发消费保护：只有仍为 ACTIVE 的码能被消费。
        if (recoveryCredentialMapper.markConsumed(credential.getId()) != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "恢复失败", UNIFIED_RECOVERY_FAILURE);
        }

        String newCode = issueNewCodeAfterPasswordChange(account.getId(), newPassword);
        auditService.record(account.getId(), "ACCOUNT", "ACCOUNT_RECOVERED", "account:" + account.getId(), null, null);
        return new RecoveryResult(newCode);
    }

    @Transactional
    public RecoveryResult reissueRecoveryCode(Long accountId, String currentPassword) {
        Account account = requireAccount(accountId);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "验证失败", "当前密码不正确。");
        }
        recoveryCredentialMapper.revokeActiveByAccountId(accountId);
        String recoveryCode = RecoveryCodes.generate();
        insertActiveCredential(accountId, recoveryCode);
        auditService.record(accountId, "ACCOUNT", "RECOVERY_CODE_REISSUED", "account:" + accountId, null, null);
        return new RecoveryResult(recoveryCode);
    }

    /** 返回递增后的认证版本，供当前会话更新绑定，其余旧会话随之失效。 */
    @Transactional
    public long changePassword(Long accountId, String currentPassword, String newPassword) {
        Account account = requireAccount(accountId);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "验证失败", "当前密码不正确。");
        }
        accountMapper.updatePasswordAndBumpVersion(accountId, passwordEncoder.encode(newPassword));
        long newVersion = requireAccount(accountId).getAuthVersion();
        auditService.record(accountId, "ACCOUNT", "PASSWORD_CHANGED", "account:" + accountId, null, null);
        return newVersion;
    }

    private String issueNewCodeAfterPasswordChange(Long accountId, String newPassword) {
        accountMapper.updatePasswordAndBumpVersion(accountId, passwordEncoder.encode(newPassword));
        String newCode = RecoveryCodes.generate();
        insertActiveCredential(accountId, newCode);
        return newCode;
    }

    private void insertActiveCredential(Long accountId, String plainCode) {
        RecoveryCredential credential = new RecoveryCredential();
        credential.setAccountId(accountId);
        credential.setCodeHash(RecoveryCodes.hash(RecoveryCodes.normalize(plainCode)));
        credential.setStatus("ACTIVE");
        recoveryCredentialMapper.insert(credential);
    }

    private Account requireAccount(Long accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "未登录或会话已失效", "请先登录后再访问该资源。");
        }
        return account;
    }
}
