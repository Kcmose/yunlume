package com.example.nav.module.site.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@TableName("site_config")
public class SiteConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String siteName;
    private String siteDescription;
    private String publishUrl;
    private String backgroundType;
    private String backgroundColor;
    private String backgroundImage;
    private String mobileBackgroundImage;
    private String fontColor;
    private Boolean backgroundEffect;
    private Boolean musicEnabled;
    private String musicUrl;
    private Boolean subscribeEnabled;
    private Boolean topContentEnabled;
    private String messageText;
    private Integer version;
    private LocalDateTime installCompletedAt;
    private UUID installInstanceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
