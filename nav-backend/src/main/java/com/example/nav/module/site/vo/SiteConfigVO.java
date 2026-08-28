package com.example.nav.module.site.vo;

import java.time.LocalDateTime;

public record SiteConfigVO(
        Long id,
        String siteName,
        String siteDescription,
        String publishUrl,
        String backgroundType,
        String backgroundColor,
        String backgroundImage,
        String mobileBackgroundImage,
        String fontColor,
        Boolean backgroundEffect,
        Boolean musicEnabled,
        String musicUrl,
        Boolean subscribeEnabled,
        Boolean topContentEnabled,
        String messageText,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
