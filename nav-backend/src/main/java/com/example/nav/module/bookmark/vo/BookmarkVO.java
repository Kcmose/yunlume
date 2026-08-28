package com.example.nav.module.bookmark.vo;

import java.time.LocalDateTime;

public record BookmarkVO(
        Long id,
        Long categoryId,
        String name,
        String url,
        String icon,
        String description,
        Integer sortOrder,
        Boolean isRecommend,
        Boolean isExternal,
        Boolean visible,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
