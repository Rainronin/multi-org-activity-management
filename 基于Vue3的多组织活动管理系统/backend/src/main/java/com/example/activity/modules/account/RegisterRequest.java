package com.example.activity.modules.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名需 3-32 位字母、数字或下划线")
    String username,
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$", message = "密码需 8-72 位且同时包含字母和数字")
    String password) {
}
