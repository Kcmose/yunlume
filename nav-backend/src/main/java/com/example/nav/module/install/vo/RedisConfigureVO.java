package com.example.nav.module.install.vo;

public record RedisConfigureVO(
        boolean configured,
        boolean restartRequired
) {
}
