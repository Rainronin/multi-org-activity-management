package com.example.activity.modules.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
    @NotBlank(message = "组织名称不能为空")
    @Size(max = 64, message = "组织名称不超过 64 字符")
    String name,
    @Size(max = 512, message = "组织简介不超过 512 字符")
    String description) {
}
