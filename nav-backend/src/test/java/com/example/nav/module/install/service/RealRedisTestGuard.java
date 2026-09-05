package com.example.nav.module.install.service;

import org.junit.jupiter.api.Assumptions;

/** Allows local skips but turns a missing real-Redis CI environment into a hard failure. */
public final class RealRedisTestGuard {
    private RealRedisTestGuard() {}

    public static void require(String hostVariable) {
        String host = System.getenv(hostVariable);
        if (host != null && !host.isBlank()) return;
        if ("true".equals(System.getenv("REDIS_REAL_TESTS_REQUIRED"))) {
            throw new AssertionError(hostVariable + " is required when REDIS_REAL_TESTS_REQUIRED=true");
        }
        Assumptions.assumeTrue(false, hostVariable + " is not configured for local real-Redis tests");
    }
}
