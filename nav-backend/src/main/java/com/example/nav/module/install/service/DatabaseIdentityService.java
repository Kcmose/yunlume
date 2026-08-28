package com.example.nav.module.install.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Order(-100)
public class DatabaseIdentityService implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final String expectedInstanceId;
    private volatile Verification verification = Verification.NOT_REQUIRED;
    private volatile long lastCheckNanos;

    public DatabaseIdentityService(
            JdbcTemplate jdbcTemplate,
            @Value("${nav.database-config.expected-instance-id:}") String expectedInstanceId
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedInstanceId = normalizeUuid(expectedInstanceId);
        if (this.expectedInstanceId != null) {
            this.verification = Verification.UNAVAILABLE;
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    public boolean isRequiredAndUnverified() {
        return expectedInstanceId != null && verification != Verification.VERIFIED;
    }

    public boolean isIdentityRequired() {
        return expectedInstanceId != null;
    }

    public boolean ensureVerified() {
        if (expectedInstanceId == null) return true;
        long now = System.nanoTime();
        if (verification == Verification.VERIFIED
                && now - lastCheckNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
            return true;
        }
        return refresh();
    }

    public synchronized boolean refresh() {
        if (expectedInstanceId == null) {
            verification = Verification.NOT_REQUIRED;
            lastCheckNanos = System.nanoTime();
            return true;
        }
        try {
            String actual = jdbcTemplate.queryForObject(
                    "SELECT install_instance_id::text FROM public.site_config LIMIT 1", String.class);
            verification = expectedInstanceId.equals(normalizeUuid(actual))
                    ? Verification.VERIFIED
                    : Verification.MISMATCH;
        } catch (RuntimeException exception) {
            verification = Verification.UNAVAILABLE;
        }
        lastCheckNanos = System.nanoTime();
        return verification == Verification.VERIFIED;
    }

    public String state() {
        return verification.name();
    }

    private String normalizeUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private enum Verification {
        NOT_REQUIRED,
        VERIFIED,
        UNAVAILABLE,
        MISMATCH
    }
}
