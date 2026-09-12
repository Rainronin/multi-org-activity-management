package com.example.activity.config;

import com.example.activity.common.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * P0 安全边界：只放行只读健康检查与匿名 CSRF 凭据端点，其余要求登录（当前无登录入口，一律 401）。
 * CSRF 使用 HttpSessionCsrfTokenRepository 并关闭掩码编码：/api/auth/csrf 返回仓库原始 token，
 * 写请求原样回传，不混用原始值与掩码值（开发规范 2.4）；注册、登录随 P1.1 按表单认证接入。
 * 过滤器层 401/403 与 Controller 层错误输出同一 ProblemDetail 结构。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/csrf").permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                .csrfTokenRequestHandler(csrfRequestHandler))
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint(this::writeUnauthorized)
                .accessDeniedHandler(this::writeAccessDenied))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private void writeUnauthorized(
        HttpServletRequest request, HttpServletResponse response,
        org.springframework.security.core.AuthenticationException ex) throws IOException {
        writeProblem(response, request, HttpStatus.UNAUTHORIZED.value(),
            "AUTH_REQUIRED", "未登录或会话已失效", "请先登录后再访问该资源。");
    }

    private void writeAccessDenied(
        HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) throws IOException {
        if (ex instanceof CsrfException || ex.getCause() instanceof CsrfException) {
            writeProblem(response, request, HttpStatus.FORBIDDEN.value(),
                "CSRF_INVALID", "CSRF 校验失败", "请刷新凭据后重试，不要自动重复提交。");
        } else {
            writeProblem(response, request, HttpStatus.FORBIDDEN.value(),
                "FORBIDDEN", "无权访问", "当前身份无权执行此操作。");
        }
    }

    private void writeProblem(
        HttpServletResponse response, HttpServletRequest request, int status, String code, String title, String detail)
        throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        body.put("requestId", requestId != null ? requestId : "");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
