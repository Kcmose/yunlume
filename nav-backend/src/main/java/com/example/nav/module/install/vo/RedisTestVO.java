package com.example.nav.module.install.vo;

import java.time.Instant;

public record RedisTestVO(
        boolean ok,
        String connectionTicket,
        Instant expiresAt
) {
}
