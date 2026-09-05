package com.example.nav.module.customlink.vo;

import java.io.Serializable;
import java.time.LocalDateTime;

public record CustomLinkVO(
        Long id,
        String title,
        String url,
        String position,
        Integer sortOrder,
        Boolean visible,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) implements Serializable {
}
