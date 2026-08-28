package com.example.nav.module.install.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InstallCompleteDTO(
        @NotBlank(message = "站点名称不能为空")
        @Size(max = 50, message = "站点名称不能超过 50 个字符")
        String siteName,

        @Size(max = 255, message = "站点简介不能超过 255 个字符")
        String siteDescription,

        @NotBlank(message = "管理员用户名不能为空")
        @Size(min = 3, max = 32, message = "管理员用户名长度应为 3-32 个字符")
        @Pattern(
                regexp = "^[A-Za-z][A-Za-z0-9._-]{2,31}$",
                message = "管理员用户名必须以英文字母开头，且只能包含英文字母、数字、点、下划线和连字符"
        )
        String username,

        @NotBlank(message = "管理员昵称不能为空")
        @Size(max = 50, message = "管理员昵称不能超过 50 个字符")
        String nickname,

        String password,
        String confirmPassword
) {
}
