package com.example.nav.module.site.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.site.service.SiteConfigService;
import com.example.nav.module.site.vo.SiteConfigVO;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.example.nav.module.upload.service.ManagedBackgroundReferences;
import com.example.nav.module.publicdata.PublicDataCacheNames;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.io.IOException;
import java.util.List;

@Service
public class SiteConfigServiceImpl implements SiteConfigService {

    private final SiteConfigMapper siteConfigMapper;
    private final PublicDataCacheInvalidator cacheInvalidator;
    private final ManagedBackgroundReferences backgroundReferences;

    public SiteConfigServiceImpl(
            SiteConfigMapper siteConfigMapper,
            PublicDataCacheInvalidator cacheInvalidator,
            UploadStorageProperties uploadProperties
    ) {
        this.siteConfigMapper = siteConfigMapper;
        this.cacheInvalidator = cacheInvalidator;
        this.backgroundReferences = new ManagedBackgroundReferences(uploadProperties);
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
        // 与 GC/导入共享 site 行锁；引用校验至配置提交期间不允许删除图片。
        SiteConfig config = requireSingleConfig(siteConfigMapper.selectAllForUpdate());
        int persistedVersion = config.getVersion();
        if (persistedVersion == Integer.MAX_VALUE) {
            throw BusinessException.conflict("站点配置版本已达到上限，无法安全更新");
        }
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
        validateBackgroundReference(effectiveBackgroundImage);
        validateBackgroundReference(dto.mobileBackgroundImage() == null
                ? config.getMobileBackgroundImage() : dto.mobileBackgroundImage());

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
        cacheInvalidator.invalidateRecorded((long) dto.expectedVersion() + 1L,
                PublicDataCacheNames.SITE_CONFIG);
        SiteConfigVO updated = toVO(siteConfigMapper.selectById(config.getId()));
        return updated;
    }

    private SiteConfig getRequiredConfig() {
        return requireSingleConfig(siteConfigMapper.selectList(Wrappers.<SiteConfig>lambdaQuery()
                .orderByAsc(SiteConfig::getId)));
    }

    private void validateBackgroundReference(String url) {
        String filename = backgroundReferences.filename(url);
        if (filename == null) return;
        try {
            backgroundReferences.requireFile(filename);
        } catch (IOException exception) {
            throw BusinessException.badRequest("受管背景图片已不存在或不可用，请重新上传后保存");
        }
    }

    private SiteConfig requireSingleConfig(List<SiteConfig> configs) {
        if (configs != null && configs.size() == 1 && configs.get(0) != null
                && Long.valueOf(1L).equals(configs.get(0).getId())
                && configs.get(0).getVersion() != null
                && configs.get(0).getVersion() >= 0) {
            return configs.get(0);
        }
        throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "站点配置必须且只能有一条有效记录，请管理员检查数据库初始化或备份恢复状态"
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
