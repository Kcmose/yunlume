package com.example.nav.module.auth.dto;

public record ChangePasswordDTO(
        String currentPassword,
        String newPassword,
        String confirmPassword
) {
}
