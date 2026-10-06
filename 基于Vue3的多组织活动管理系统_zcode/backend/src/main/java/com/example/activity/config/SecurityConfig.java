package com.example.activity.config;

import com.example.activity.security.AccessMapper;
import com.example.activity.security.AccountUserDetails;
import com.example.activity.security.AccountUserDetailsService;
import com.example.activity.security.AuthRateLimiter;
import com.example.activity.security.AuthRateLimitFilter;
import com.example.activity.security.AuthVersionFilter;
import com.example.activity.security.ProblemResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * 安全边界：注册、恢复匿名可用（CSRF 不豁免）；登录走 Spring Security 表单过滤器（204 成功、
 * ProblemDetail 失败）；退出复用框架能力；每个已认证请求经 AuthVersionFilter 核对认证版本。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final AuthRateLimiter rateLimiter;
    private final AccountUserDetailsService userDetailsService;
    private final AccessMapper accessMapper;

    public SecurityConfig(
        ObjectMapper objectMapper,
        AuthRateLimiter rateLimiter,
        AccountUserDetailsService userDetailsService,
        AccessMapper accessMapper) {
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.userDetailsService = userDetailsService;
        this.accessMapper = accessMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authenticationManager)
        throws Exception {
        // 关闭掩码编码：/api/auth/csrf 返回仓库原始 token，写请求原样回传，不混用原始值与掩码值。
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
            .authenticationManager(authenticationManager)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/csrf", "/api/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/recover").permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                .csrfTokenRequestHandler(csrfRequestHandler))
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler(this::loginSuccess)
                .failureHandler(this::loginFailure)
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) ->
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                .permitAll())
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(this::writeUnauthorized)
                .accessDeniedHandler(this::writeAccessDenied))
            .addFilterAfter(new AuthVersionFilter(accessMapper), SecurityContextHolderFilter.class)
            .addFilterBefore(
                new AuthRateLimitFilter(rateLimiter, objectMapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void loginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
        throws IOException {
        AccountUserDetails details = (AccountUserDetails) authentication.getPrincipal();
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthVersionFilter.SESSION_KEY, details.getAuthVersion());
        rateLimiter.reset(AuthRateLimitFilter.loginKey(request));
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        response.setHeader("Cache-Control", "no-store");
    }

    private void loginFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
        throws IOException {
        AuthRateLimiter.Decision decision =
            rateLimiter.tryAcquire(AuthRateLimitFilter.loginKey(request), AuthRateLimitFilter.LOGIN_MAX_ATTEMPTS);
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            ProblemResponses.write(response, objectMapper, request, 429, "RATE_LIMITED",
                "请求过于频繁", "登录尝试过于频繁，请稍后再试。");
            return;
        }
        ProblemResponses.write(response, objectMapper, request, 401, "AUTH_INVALID_CREDENTIALS",
            "登录失败", "账号或密码不正确。");
    }

    private void writeUnauthorized(
        HttpServletRequest request, HttpServletResponse response,
        org.springframework.security.core.AuthenticationException ex) throws IOException {
        ProblemResponses.write(response, objectMapper, request, HttpStatus.UNAUTHORIZED.value(),
            "AUTH_REQUIRED", "未登录或会话已失效", "请先登录后再访问该资源。");
    }

    private void writeAccessDenied(
        HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        if (ex instanceof CsrfException || ex.getCause() instanceof CsrfException) {
            ProblemResponses.write(response, objectMapper, request, HttpStatus.FORBIDDEN.value(),
                "CSRF_INVALID", "CSRF 校验失败", "请刷新凭据后重试，不要自动重复提交。");
        } else {
            ProblemResponses.write(response, objectMapper, request, HttpStatus.FORBIDDEN.value(),
                "FORBIDDEN", "无权访问", "当前身份无权执行此操作。");
        }
    }
}
