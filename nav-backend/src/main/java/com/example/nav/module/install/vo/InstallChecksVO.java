package com.example.nav.module.install.vo;

public record InstallChecksVO(
        InstallCheckVO database,
        InstallCheckVO schema,
        InstallCheckVO siteConfig,
        InstallCheckVO upload,
        InstallCheckVO redis
) {
}
