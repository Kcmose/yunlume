package com.example.nav.module.search.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SearchEngineDTO(
        @NotBlank(message = "搜索引擎名称不能为空")
        @Size(max = 50, message = "搜索引擎名称不能超过 50 个字符")
        String name,

        @Size(max = 255, message = "搜索引擎图标不能超过 255 个字符")
        String icon,

        @NotBlank(message = "搜索地址模板不能为空")
        @Size(max = 500, message = "搜索地址模板不能超过 500 个字符")
        @Pattern(regexp = "(?i)^https?://[^\\s\"'\\\\]+$", message = "搜索地址模板必须是安全的 HTTP(S) 地址")
        String searchUrl,

        @Size(max = 100, message = "占位文字不能超过 100 个字符")
        String placeholder,

        @Min(value = 0, message = "排序值不能小于 0")
        Integer sortOrder,

        Boolean visible
) {
}
