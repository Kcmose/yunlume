package com.example.nav.module.site.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.site.service.SiteConfigService;
import com.example.nav.module.site.vo.SiteConfigVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

@Service
public class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigMapper siteConfigMapper;

    public SiteConfigServiceImpl(SiteConfigMapper siteConfigMapper) {
        this.siteConfigMapper = siteConfigMapper;
    }

    @Override
    @Transactional
    public SiteConfigVO getConfig() {
        return toVO(getRequiredConfig());
    }

    @Override
    @Transactional
    public SiteConfigVO update(SiteConfigUpdateDTO dto) {
        if (dto == null || dto.expectedVersion() == null) {
            throw BusinessException.badRequest("配置版本不能为空");
        }
        SiteConfig config = getRequiredConfig();
        int persistedVersion = config.getVersion() == null ? 0 : config.getVersion();
        if (persistedVersion != dto.expectedVersion()) {
            throw concurrentUpdate();
        }

        String normalizedSiteName = dto.siteName() == null ? null : dto.siteName().trim();
        if (normalizedSiteName != null && normalizedSiteName.isEmpty()) {
            throw BusinessException.badRequest("站点名称不能为空");
        }

        String effectiveBackgroundType = dto.backgroundType() == null
                ? config.getBackgroundType()
                : dto.backgroundType();
        String effectiveBackgroundImage = dto.backgroundImage() == null
                ? config.getBackgroundImage()
                : dto.backgroundImage();
        if ("image".equals(effectiveBackgroundType)
                && (effectiveBackgroundImage == null || effectiveBackgroundImage.isBlank())) {
            throw BusinessException.badRequest("图片背景模式必须配置 PC 背景图片");
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<SiteConfig> update = Wrappers.<SiteConfig>lambdaUpdate()
                .eq(SiteConfig::getId, config.getId())
                .eq(SiteConfig::getVersion, dto.expectedVersion())
                .set(SiteConfig::getUpdatedAt, now)
                .setSql("version = version + 1");
        if (normalizedSiteName != null) update.set(SiteConfig::getSiteName, normalizedSiteName);
        if (dto.siteDescription() != null) update.set(SiteConfig::getSiteDescription, dto.siteDescription());
        if (dto.publishUrl() != null) update.set(SiteConfig::getPublishUrl, dto.publishUrl());
        if (dto.backgroundType() != null) update.set(SiteConfig::getBackgroundType, dto.backgroundType());
        if (dto.backgroundColor() != null) update.set(SiteConfig::getBackgroundColor, dto.backgroundColor());
        if (dto.backgroundImage() != null) update.set(SiteConfig::getBackgroundImage, dto.backgroundImage());
        if (dto.mobileBackgroundImage() != null) {
            update.set(SiteConfig::getMobileBackgroundImage, dto.mobileBackgroundImage());
        }
        if (dto.fontColor() != null) update.set(SiteConfig::getFontColor, dto.fontColor());
        if (dto.backgroundEffect() != null) update.set(SiteConfig::getBackgroundEffect, dto.backgroundEffect());
        if (dto.musicEnabled() != null) update.set(SiteConfig::getMusicEnabled, dto.musicEnabled());
        if (dto.musicUrl() != null) update.set(SiteConfig::getMusicUrl, dto.musicUrl());
        if (dto.subscribeEnabled() != null) update.set(SiteConfig::getSubscribeEnabled, dto.subscribeEnabled());
        if (dto.topContentEnabled() != null) update.set(SiteConfig::getTopContentEnabled, dto.topContentEnabled());
        if (dto.messageText() != null) update.set(SiteConfig::getMessageText, dto.messageText());

        if (siteConfigMapper.update(null, update) != 1) {
            throw concurrentUpdate();
        }
        return toVO(siteConfigMapper.selectById(config.getId()));
    }

    private SiteConfig getRequiredConfig() {
        SiteConfig config = siteConfigMapper.selectOne(Wrappers.<SiteConfig>lambdaQuery()
                .orderByAsc(SiteConfig::getId)
                .last("LIMIT 1"));
        if (config != null) {
            return config;
        }
        throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "站点配置不存在，请管理员检查数据库初始化或备份恢复状态"
        );
    }

    private SiteConfigVO toVO(SiteConfig config) {
        return new SiteConfigVO(
                config.getId(), config.getSiteName(), emptyIfNull(config.getSiteDescription()),
                emptyIfNull(config.getPublishUrl()), config.getBackgroundType(), config.getBackgroundColor(),
                emptyIfNull(config.getBackgroundImage()),
                emptyIfNull(config.getMobileBackgroundImage()),
                config.getFontColor(), config.getBackgroundEffect(), config.getMusicEnabled(),
                emptyIfNull(config.getMusicUrl()), config.getSubscribeEnabled(), config.getTopContentEnabled(),
                emptyIfNull(config.getMessageText()), config.getVersion() == null ? 0 : config.getVersion(),
                config.getCreatedAt(), config.getUpdatedAt()
        );
    }

    private BusinessException concurrentUpdate() {
        return BusinessException.conflict("站点配置已被其他会话修改，请刷新后重试");
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
