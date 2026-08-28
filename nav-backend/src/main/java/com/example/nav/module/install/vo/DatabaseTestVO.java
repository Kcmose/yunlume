package com.example.nav.module.install.vo;

import java.time.Instant;

public record DatabaseTestVO(
        boolean ok,
        String connectionTicket,
        Instant expiresAt,
        String schemaState,
        boolean requiresInitialization
) {
}
