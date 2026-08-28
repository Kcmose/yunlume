package com.example.nav.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nav.database-install")
public class DatabaseInstallProperties {

    private Source source = Source.LEGACY_ENV;
    private String configFile = "/app/config/database.properties";
    private String configuredMarkerFile = "/app/config/database.configured";
    private String completedMarkerFile = "/app/config/install.completed";
    private String caCertificateFile = "/app/config/postgresql-ca.pem";
    private long ticketTtlSeconds = 300;
    private boolean autoRestart;
    private boolean allowInsecureSetup;

    public enum Source {
        UNCONFIGURED,
        LEGACY_ENV
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public String getConfiguredMarkerFile() {
        return configuredMarkerFile;
    }

    public void setConfiguredMarkerFile(String configuredMarkerFile) {
        this.configuredMarkerFile = configuredMarkerFile;
    }

    public String getCompletedMarkerFile() {
        return completedMarkerFile;
    }

    public void setCompletedMarkerFile(String completedMarkerFile) {
        this.completedMarkerFile = completedMarkerFile;
    }

    public String getCaCertificateFile() {
        return caCertificateFile;
    }

    public void setCaCertificateFile(String caCertificateFile) {
        this.caCertificateFile = caCertificateFile;
    }

    public long getTicketTtlSeconds() {
        return ticketTtlSeconds;
    }

    public void setTicketTtlSeconds(long ticketTtlSeconds) {
        this.ticketTtlSeconds = ticketTtlSeconds;
    }

    public boolean isAutoRestart() {
        return autoRestart;
    }

    public void setAutoRestart(boolean autoRestart) {
        this.autoRestart = autoRestart;
    }

    public boolean isAllowInsecureSetup() {
        return allowInsecureSetup;
    }

    public void setAllowInsecureSetup(boolean allowInsecureSetup) {
        this.allowInsecureSetup = allowInsecureSetup;
    }
}
