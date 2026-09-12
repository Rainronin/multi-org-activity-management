package com.example.activity.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求标识：每次 HTTP 请求生成或沿用合法的 X-Request-Id，写入响应头、MDC 与请求属性。
 * 仅用于追踪单次请求，不作为防重键（开发规范 2.5）。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "com.example.activity.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String requestId = incoming != null && incoming.matches("[A-Za-z0-9-]{1,64}")
            ? incoming
            : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(HEADER_NAME, requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
