package com.example.nav.common.dto;

import jakarta.validation.constraints.NotNull;

public record VisibilityDTO(
        @NotNull(message = "visible 不能为空")
        Boolean visible
) {
}
