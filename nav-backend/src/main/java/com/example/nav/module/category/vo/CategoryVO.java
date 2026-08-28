package com.example.nav.module.category.vo;

import java.time.LocalDateTime;

public record CategoryVO(
        Long id,
        String name,
        String icon,
        Integer sortOrder,
        Boolean visible,
        long bookmarkCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
