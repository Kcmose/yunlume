package com.example.nav.module.customlink.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomLinkDTO(
        @NotBlank(message = "链接文字不能为空")
        @Size(max = 50, message = "链接文字不能超过 50 个字符")
        String title,

        @NotBlank(message = "链接地址不能为空")
        @Size(max = 500, message = "链接地址不能超过 500 个字符")
        String url,

        @NotBlank(message = "显示位置不能为空")
        @Pattern(regexp = "^\\s*(header|footer)\\s*$", message = "显示位置只能是 header 或 footer")
        String position,

        @Min(value = 0, message = "排序值不能小于 0")
        Integer sortOrder,

        Boolean visible
) {
}
