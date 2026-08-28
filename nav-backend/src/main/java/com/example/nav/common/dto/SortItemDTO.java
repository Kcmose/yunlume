package com.example.nav.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SortItemDTO(
        @NotNull(message = "ID 不能为空")
        @Positive(message = "ID 必须大于 0")
        Long id,

        @NotNull(message = "排序值不能为空")
        @Min(value = 0, message = "排序值不能小于 0")
        Integer sortOrder
) {
}
