package com.example.activity.modules.account;

import jakarta.validation.constraints.NotBlank;

public record ReissueRequest(@NotBlank(message = "当前密码不能为空") String currentPassword) {
}
