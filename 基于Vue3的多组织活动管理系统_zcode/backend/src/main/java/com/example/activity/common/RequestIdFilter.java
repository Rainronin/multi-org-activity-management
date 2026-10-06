package com.example.activity.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 请求标识：每次 HTTP 请求由服务端生成 X-Request-Id（不沿用客户端传入，开发规范 2.5），
 * 写入响应头、MDC 与请求属性。仅用于追踪单次请求，不作为防重键。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "com.example.activity.requestId";
    public static final String ERROR_CODE_ATTRIBUTE = "com.example.activity.errorCode";
    public static final String FAILURE_ATTRIBUTE = "com.example.activity.failure";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");
    private static final Set<String> SECURITY_ROUTES = Set.of(
        "/api/auth/login", "/api/auth/logout", "/api/auth/register", "/api/auth/recover");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(HEADER_NAME, requestId);
        MDC.put("requestId", requestId);
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error failure) {
            request.setAttribute(FAILURE_ATTRIBUTE, failure);
            request.setAttribute(ERROR_CODE_ATTRIBUTE, "INTERNAL_ERROR");
            throw failure;
        } finally {
            try {
                logCompletion(request, response, started);
            } finally {
                MDC.remove("requestId");
            }
        }
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long started) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String route = pattern == null
            ? (SECURITY_ROUTES.contains(path) ? path : "UNMAPPED")
            : pattern.toString();
        String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        Throwable failure = (Throwable) request.getAttribute(FAILURE_ATTRIBUTE);
        int status = failure == null ? response.getStatus() : 500;
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        Object errorCode = request.getAttribute(ERROR_CODE_ATTRIBUTE);
        Object code = errorCode == null ? (status >= 400 ? "HTTP_ERROR" : "NONE") : errorCode;
        String message = "请求完成 method={} route={} durationMs={} status={} code={}";
        if (failure != null) {
            log.error(message, method, route, durationMs, status, code, safeStack(failure));
        } else if (status >= 400) {
            log.warn(message, method, route, durationMs, status, code);
        } else {
            log.info(message, method, route, durationMs, status, code);
        }
    }

    /** 异常消息可能含SQL、正文或上游密钥；仅保留类型和堆栈，原因环按身份去重。 */
    private Throwable safeStack(Throwable failure) {
        Throwable safe = new Throwable(failure.getClass().getName());
        Throwable cursor = safe;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(failure);
        cursor.setStackTrace(failure.getStackTrace());
        while (failure.getCause() != null && visited.add(failure.getCause())) {
            failure = failure.getCause();
            Throwable cause = new Throwable(failure.getClass().getName());
            cause.setStackTrace(failure.getStackTrace());
            cursor.initCause(cause);
            cursor = cause;
        }
        return safe;
    }
}
