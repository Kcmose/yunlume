package com.example.nav.module.install.vo;

public record DatabaseConfigureVO(
        boolean configured,
        boolean initialized,
        boolean installed,
        boolean restartRequired
) {
}
