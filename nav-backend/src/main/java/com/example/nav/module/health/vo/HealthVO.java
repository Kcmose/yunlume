package com.example.nav.module.health.vo;

import java.time.Instant;

public record HealthVO(String status, String service, Instant timestamp) {
}
