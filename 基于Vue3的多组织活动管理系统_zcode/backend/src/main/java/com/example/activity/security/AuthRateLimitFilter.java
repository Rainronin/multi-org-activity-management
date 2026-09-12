package com.example.activity.security;

import com.example.activity.common.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证入口限流：登录按"只检查（失败时再计数）"，恢复与注册按"每次尝试计数"。
 * 超限直接返回 429 与 Retry-After，不进入认证流程。
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    public static final int LOGIN_MAX_ATTEMPTS = 10;
    public static final int RECOVER_MAX_ATTEMPTS = 10;
    public static final int REGISTER_MAX_ATTEMPTS = 10;

    private final AuthRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(AuthRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (new AntPathRequestMatcher("/api/auth/login", HttpMethod.POST.name()).matches(request)) {
            AuthRateLimiter.Decision decision =
                rateLimiter.checkOnly(loginKey(request), LOGIN_MAX_ATTEMPTS);
            if (!decision.allowed()) {
                reject(response, request, decision);
                return;
            }
        } else if (new AntPathRequestMatcher("/api/auth/recover", HttpMethod.POST.name()).matches(request)) {
            AuthRateLimiter.Decision decision =
                rateLimiter.tryAcquire("recover:" + request.getParameter("username") + ":" + clientIp(request), RECOVER_MAX_ATTEMPTS);
            if (!decision.allowed()) {
                reject(response, request, decision);
                return;
            }
        } else if (new AntPathRequestMatcher("/api/auth/register", HttpMethod.POST.name()).matches(request)) {
            AuthRateLimiter.Decision decision =
                rateLimiter.tryAcquire("register:" + clientIp(request), REGISTER_MAX_ATTEMPTS);
            if (!decision.allowed()) {
                reject(response, request, decision);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    public static String loginKey(HttpServletRequest request) {
        return "login:" + request.getParameter("username") + ":" + clientIp(request);
    }

    public static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, HttpServletRequest request, AuthRateLimiter.Decision decision)
        throws IOException {
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        ProblemResponses.write(response, objectMapper, request, 429, "RATE_LIMITED",
            "请求过于频繁", "尝试过于频繁，请稍后再试。");
    }
}
