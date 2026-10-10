package com.example.activity.security;

import com.example.activity.modules.account.Account;
import com.example.activity.modules.account.AccountMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 表单认证数据源。未知账号与停用账号统一抛 UsernameNotFoundException，
 * 由 DaoAuthenticationProvider 归并为 BadCredentialsException，避免枚举账号（开发规范 2.4）。
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountMapper accountMapper;

    public AccountUserDetailsService(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = accountMapper.findByUsername(username);
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            throw new UsernameNotFoundException("账号不存在或已停用");
        }
        return new AccountUserDetails(
            account.getId(), account.getUsername(), account.getPasswordHash(), account.getAuthVersion(), true);
    }
}
