package com.example.nav.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nav.jwt")
public class JwtProperties {

    private String secret;
    @Min(value = 5, message = "JWT expiration must be at least 5 minutes")
    @Max(value = 10080, message = "JWT expiration must not exceed 7 days")
    private long expirationMinutes = 120;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    public void setExpirationMinutes(long expirationMinutes) {
        this.expirationMinutes = expirationMinutes;
    }
}
