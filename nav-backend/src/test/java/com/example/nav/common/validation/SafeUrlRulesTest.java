package com.example.nav.common.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeUrlRulesTest {

    @Test
    void httpUrlsRejectUserInfoAndUnsafeCharacters() {
        assertThat(SafeUrlRules.isSafeHttp("https://example.com/path?q=1")).isTrue();
        assertThat(SafeUrlRules.isSafeHttp("https://user:secret@example.com/path")).isFalse();
        assertThat(SafeUrlRules.isSafeHttp("javascript:alert(1)")).isFalse();
        assertThat(SafeUrlRules.isSafeHttp("https://example.com/a b")).isFalse();
    }

    @Test
    void internalUrlsMustBeAbsolutePathsWithoutAuthority() {
        assertThat(SafeUrlRules.isSafeHttpOrInternal("/uploads/background.png")).isTrue();
        assertThat(SafeUrlRules.isSafeHttpOrInternal("//evil.example/path")).isFalse();
        assertThat(SafeUrlRules.isSafeHttpOrInternal("relative/path")).isFalse();
    }
}
