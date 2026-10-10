package com.example.activity.modules.account;

import com.example.activity.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号模块：P0.3 仅提供匿名 CSRF 凭据获取（开发规范 2.4）。
 * 注册、恢复码、登录与当前身份端点随 P1.1 落地；登录由 Spring Security 表单过滤器处理，不在本控制器重复声明。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CSRF_TOKEN_UNAVAILABLE", "无法获取凭据", "请稍后重试。");
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }
}
