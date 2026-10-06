package com.example.activity.security;

import com.example.activity.common.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;

/** 安全层输出统一 ProblemDetail（与 GlobalExceptionHandler 同构，开发规范 2.3）。 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(
        HttpServletResponse response, ObjectMapper objectMapper, HttpServletRequest request,
        int status, String code, String title, String detail) throws IOException {
        response.setStatus(status);
        request.setAttribute(RequestIdFilter.ERROR_CODE_ATTRIBUTE, code);
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
