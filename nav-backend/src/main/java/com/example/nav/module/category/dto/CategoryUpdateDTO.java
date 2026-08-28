package com.example.nav.module.category.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryUpdateDTO(
        @NotBlank(message = "分类名称不能为空")
        @Size(max = 50, message = "分类名称不能超过 50 个字符")
        String name,

        @Size(max = 100, message = "图标不能超过 100 个字符")
        String icon,

        @Min(value = 0, message = "排序值不能小于 0")
        Integer sortOrder,

        Boolean visible
) {
}
