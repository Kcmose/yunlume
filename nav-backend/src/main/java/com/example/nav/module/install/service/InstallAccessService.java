package com.example.nav.module.install.service;

import com.example.nav.common.config.WebInstallProperties;
import com.example.nav.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InstallAccessService {

    private final WebInstallProperties properties;

    public InstallAccessService(WebInstallProperties properties) {
        this.properties = properties;
    }

    public void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "网页安装功能已关闭");
        }
    }
}
