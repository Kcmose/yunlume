package com.example.nav.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nav.redis-install")
public class RedisInstallProperties {

    private Source source = Source.LEGACY_ENV;
    private String configFile = "/app/config/redis.properties";
    private String configuredMarkerFile = "/app/config/redis.configured";
    private String caCertificateFile = "/app/config/redis-ca.pem";
    private long ticketTtlSeconds = 300;
    private boolean autoRestart;

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
}
