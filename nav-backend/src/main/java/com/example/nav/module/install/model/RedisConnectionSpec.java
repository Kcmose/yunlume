package com.example.nav.module.install.model;

import java.time.Duration;
import java.util.List;

public record RedisConnectionSpec(
        String host,
        int port,
        String username,
        String password,
        int database,
        RedisTlsMode tlsMode,
        String caCertificatePem,
        Duration connectTimeout,
        Duration readTimeout,
        List<String> resolvedAddresses
) {
    public RedisConnectionSpec {
        resolvedAddresses = resolvedAddresses == null ? List.of() : List.copyOf(resolvedAddresses);
    }

    @Override
    public String toString() {
        return "RedisConnectionSpec[host=<redacted>, port=" + port
                + ", username=<redacted>, password=<redacted>, database=" + database
                + ", tlsMode=" + tlsMode + ", caCertificatePem=<redacted>"
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", resolvedAddresses=<redacted>]";
    }
}
