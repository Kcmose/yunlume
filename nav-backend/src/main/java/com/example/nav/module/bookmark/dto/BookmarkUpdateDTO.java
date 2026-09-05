package com.example.nav.module.bookmark.dto;

import com.example.nav.common.validation.SafeUrl;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Size;

public record BookmarkUpdateDTO(
        @NotNull(message = "分类 ID 不能为空")
        Long categoryId,

        @NotBlank(message = "书签名称不能为空")
        @Size(max = 100, message = "书签名称不能超过 100 个字符")
        String name,

        @NotBlank(message = "书签地址不能为空")
        @Size(max = 500, message = "书签地址不能超过 500 个字符")
        @SafeUrl(allowInternal = false, allowBlank = false, message = "书签地址必须是安全的完整 HTTP(S) 地址")
        String url,

        @Size(max = 255, message = "图标地址不能超过 255 个字符")
        String icon,

        @Size(max = 255, message = "书签描述不能超过 255 个字符")
        String description,

        @Min(value = 0, message = "排序值不能小于 0")
        Integer sortOrder,

        Boolean isRecommend,
        Boolean isExternal,
        Boolean visible
) {
}
