package com.example.activity.modules.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RecoverRequest(
    @NotBlank(message = "用户名不能为空")
    String username,
    @NotBlank(message = "恢复码不能为空")
    String recoveryCode,
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$", message = "密码需 8-72 位且同时包含字母和数字")
    String newPassword) {
}
