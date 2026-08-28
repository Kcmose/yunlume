package com.example.nav.module.install.model;

public record InstallCommand(
        String siteName,
        String siteDescription,
        String username,
        String nickname,
        String encodedPassword,
        String expectedDatabaseInstanceId
) {
    @Override
    public String toString() {
        return "InstallCommand[siteName=" + siteName
                + ", siteDescription=" + siteDescription
                + ", username=" + username
                + ", nickname=" + nickname
                + ", encodedPassword=<redacted>"
                + ", expectedDatabaseInstanceId=<redacted>]";
    }
}
