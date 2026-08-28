package com.example.nav.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nav.bootstrap")
public class BootstrapProperties {

    private boolean enabled = true;
    private boolean demoDataEnabled;
    private String adminUsername = "admin";
    private String adminPassword;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDemoDataEnabled() {
        return demoDataEnabled;
    }

    public void setDemoDataEnabled(boolean demoDataEnabled) {
        this.demoDataEnabled = demoDataEnabled;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }
}
