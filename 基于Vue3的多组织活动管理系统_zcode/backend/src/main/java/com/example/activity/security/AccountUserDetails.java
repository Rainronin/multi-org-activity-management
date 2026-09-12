package com.example.activity.security;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 认证主体：仅携带账号标识、认证版本与必要状态；
 * 组织角色不在此表达（权限按当前组织与资源另行判断，后端架构 4.2）。
 */
public class AccountUserDetails implements UserDetails {

    private final Long accountId;
    private final String username;
    private final String passwordHash;
    private final long authVersion;
    private final boolean active;

    public AccountUserDetails(Long accountId, String username, String passwordHash, long authVersion, boolean active) {
        this.accountId = accountId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.authVersion = authVersion;
        this.active = active;
    }

    public Long getAccountId() {
        return accountId;
    }

    public long getAuthVersion() {
        return authVersion;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
