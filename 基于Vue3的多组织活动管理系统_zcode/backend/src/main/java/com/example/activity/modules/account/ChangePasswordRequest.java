package com.example.activity.modules.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChangePasswordRequest(
    @NotBlank(message = "当前密码不能为空")
    String currentPassword,
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$", message = "密码需 8-72 位且同时包含字母和数字")
    String newPassword) {
}
