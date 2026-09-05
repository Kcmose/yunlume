package com.example.nav.module.site.service.impl;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteConfigServiceSingletonTest {
    @Test
    void readRejectsDuplicateRowsInsteadOfChoosingLimitOne() {
        SiteConfigMapper mapper = mock(SiteConfigMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(new SiteConfig(), new SiteConfig()));
        var service = new SiteConfigServiceImpl(mapper, mock(PublicDataCacheInvalidator.class),
                new UploadStorageProperties());

        assertThrows(BusinessException.class, service::getConfig);
        verify(mapper).selectList(any());
    }
}
