package com.example.nav.module.site.dto;

import com.example.nav.common.validation.SafeUrl;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SiteConfigUpdateDTO(
        @Size(min = 1, max = 50, message = "站点名称长度应为 1-50 个字符")
        String siteName,

        @Size(max = 255, message = "站点简介不能超过 255 个字符")
        String siteDescription,

        @Size(max = 255, message = "发布地址不能超过 255 个字符")
        @SafeUrl
        String publishUrl,

        @Pattern(regexp = "color|image", message = "背景类型只能是 color 或 image")
        String backgroundType,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "背景颜色必须是六位十六进制颜色")
        String backgroundColor,

        @Size(max = 500, message = "背景图片地址不能超过 500 个字符")
        @SafeUrl(message = "PC 背景图片必须是安全的 HTTP(S) 地址或站内绝对路径")
        String backgroundImage,

        @Size(max = 500, message = "移动端背景图片地址不能超过 500 个字符")
        @SafeUrl(message = "移动端背景图片必须是安全的 HTTP(S) 地址或站内绝对路径")
        String mobileBackgroundImage,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "字体颜色必须是六位十六进制颜色")
        String fontColor,

        Boolean backgroundEffect,
        Boolean musicEnabled,

        @Size(max = 500, message = "音乐地址不能超过 500 个字符")
        @SafeUrl
        String musicUrl,

        Boolean subscribeEnabled,
        Boolean topContentEnabled,

        @Size(max = 100, message = "留言内容不能超过 100 个字符")
        String messageText,

        @NotNull(message = "配置版本不能为空")
        @PositiveOrZero(message = "配置版本不能小于 0")
        Integer expectedVersion
) {
}
