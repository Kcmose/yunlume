package com.example.nav.module.install.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RedisConfigureDTO(
        @NotBlank(message = "Redis 连接票据不能为空")
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "Redis 连接票据格式错误")
        String connectionTicket
) {
}
