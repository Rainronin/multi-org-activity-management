package com.example.activity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证版本核对：每个已认证请求比对会话绑定的 auth_version 与账号当前版本，
 * 版本不一致（改密／恢复后）立即失效会话并清除上下文，由后续授权逻辑按未登录处理（后端架构 4.1）。
 */
public class AuthVersionFilter extends OncePerRequestFilter {

    public static final String SESSION_KEY = "com.example.activity.authVersion";

    private final AccessMapper accessMapper;

    public AuthVersionFilter(AccessMapper accessMapper) {
        this.accessMapper = accessMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof AccountUserDetails details) {
            Long currentVersion = accessMapper.selectAuthVersionById(details.getAccountId());
            HttpSession session = request.getSession(false);
            Object boundVersion = session == null ? null : session.getAttribute(SESSION_KEY);
            if (currentVersion == null || !(boundVersion instanceof Number number)
                || number.longValue() != currentVersion) {
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
