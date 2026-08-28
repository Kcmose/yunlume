package com.example.nav.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deployment-facing Redis settings. Spring Boot uses the same environment
 * values under {@code spring.data.redis}; this separate binding exists so the
 * production profile can reject incomplete or accidentally insecure startup
 * without ever rendering credentials in an error message.
 */
@ConfigurationProperties(prefix = "nav.external-redis")
public class ExternalRedisProperties {

    private String host;
    private int port = 6379;
    private String username;
    private String password;
    private int database;
    private boolean sslEnabled = true;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(3);

    public void validateForProduction(String cacheType) {
        if (!"redis".equalsIgnoreCase(normalize(cacheType))) {
            throw invalid("CACHE_TYPE must be redis in the production profile");
        }
        if (normalize(host).isEmpty()) {
            throw invalid("REDIS_HOST is required");
        }
        if (hasUnsafeCharacters(host)) {
            throw invalid("REDIS_HOST contains unsupported characters");
        }
        if (port < 1 || port > 65535) {
            throw invalid("REDIS_PORT must be between 1 and 65535");
        }
        if (!normalize(username).isEmpty() && hasUnsafeCharacters(username)) {
            throw invalid("REDIS_USERNAME contains unsupported characters");
        }
        if (normalize(password).isEmpty()) {
            throw invalid("REDIS_PASSWORD is required");
        }
        if (database < 0 || database > 65535) {
            throw invalid("REDIS_DATABASE must be between 0 and 65535");
        }
        validateTimeout(connectTimeout, "REDIS_CONNECT_TIMEOUT");
        validateTimeout(readTimeout, "REDIS_READ_TIMEOUT");
    }

    private void validateTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw invalid(name + " must be greater than zero and no more than 60 seconds");
        }
    }

    private boolean hasUnsafeCharacters(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.isWhitespace(codePoint));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException("External Redis configuration is invalid: " + message);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getDatabase() {
        return database;
    }

    public void setDatabase(int database) {
        this.database = database;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
