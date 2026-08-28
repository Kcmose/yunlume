package com.example.nav.module.install.model;

public enum DatabaseSslMode {
    DISABLE("disable"),
    PREFER("prefer"),
    REQUIRE("require"),
    VERIFY_CA("verify-ca"),
    VERIFY_FULL("verify-full");

    private final String jdbcValue;

    DatabaseSslMode(String jdbcValue) {
        this.jdbcValue = jdbcValue;
    }

    public String jdbcValue() {
        return jdbcValue;
    }
}
