package com.example.nav.module.install.vo;

public record InstallStatusVO(
        String state,
        boolean installationRequired,
        boolean webInstallEnabled,
        boolean ready
) {
}
