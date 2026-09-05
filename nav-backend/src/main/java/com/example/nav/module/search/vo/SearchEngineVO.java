package com.example.nav.module.search.vo;

import java.io.Serializable;

public record SearchEngineVO(
        Long id,
        String name,
        String icon,
        String searchUrl,
        String placeholder,
        Boolean isDefault,
        Integer sortOrder,
        Boolean visible
) implements Serializable {
}
