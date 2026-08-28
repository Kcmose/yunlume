package com.example.nav.module.bookmark.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BookmarkBatchMoveDTO(
        @NotEmpty(message = "书签 ID 列表不能为空")
        @Size(max = 1000, message = "单次最多移动 1000 个书签")
        List<@NotNull(message = "书签 ID 不能为空") @Positive(message = "书签 ID 必须大于 0") Long> ids,

        @NotNull(message = "目标分类 ID 不能为空")
        @Positive(message = "目标分类 ID 必须大于 0")
        Long categoryId
) {
}
