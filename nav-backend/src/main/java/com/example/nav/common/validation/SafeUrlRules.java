package com.example.nav.common.validation;

import java.net.URI;

public final class SafeUrlRules {

    private SafeUrlRules() {
    }

    public static boolean isSafeHttpOrInternal(String value) {
        if (value == null || value.isBlank() || hasUnsafeCharacters(value)) return false;
        if (value.startsWith("/") && !value.startsWith("//")) {
            try {
                URI uri = URI.create(value);
                return !uri.isAbsolute()
                        && uri.getRawAuthority() == null
                        && uri.getRawPath() != null
                        && uri.getRawPath().startsWith("/");
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return isSafeHttp(value);
    }

    public static boolean isSafeHttp(String value) {
        if (value == null || value.isBlank() || hasUnsafeCharacters(value)) return false;
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasUnsafeCharacters(String value) {
        return value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(code -> Character.isWhitespace(code) || Character.isISOControl(code));
    }
}
