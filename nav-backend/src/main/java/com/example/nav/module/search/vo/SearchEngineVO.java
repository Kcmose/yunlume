package com.example.nav.module.search.vo;

public record SearchEngineVO(
        Long id,
        String name,
        String icon,
        String searchUrl,
        String placeholder,
        Boolean isDefault,
        Integer sortOrder,
        Boolean visible
) {
}
