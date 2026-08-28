package com.example.nav.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared structural password policy for bootstrap credentials and password changes.
 * Checks that depend on persisted state, such as password reuse, stay in the caller.
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    public static Optional<String> findViolation(String username, String password) {
        if (password == null || password.isEmpty()) {
            return Optional.of("密码不能为空");
        }

        int utf8Bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > 72) {
            return Optional.of("密码经 UTF-8 编码后不能超过 72 字节");
        }

        int characterCount = password.codePointCount(0, password.length());
        if (characterCount < 12 || characterCount > 72) {
            return Optional.of("密码长度必须为 12-72 个字符");
        }
        if (password.codePoints().anyMatch(PasswordPolicy::isDisallowedWhitespace)) {
            return Optional.of("密码不能包含空白字符");
        }

        String normalizedUsername = username == null ? "" : username.toLowerCase(Locale.ROOT);
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        if (!normalizedUsername.isEmpty() && normalizedPassword.contains(normalizedUsername)) {
            return Optional.of("密码不能与用户名相同或包含用户名");
        }

        int categoryCount = 0;
        if (password.codePoints().anyMatch(Character::isLowerCase)) categoryCount++;
        if (password.codePoints().anyMatch(Character::isUpperCase)) categoryCount++;
        if (password.codePoints().anyMatch(Character::isDigit)) categoryCount++;
        if (password.codePoints().anyMatch(codePoint -> !Character.isLetterOrDigit(codePoint))) categoryCount++;
        if (categoryCount < 3) {
            return Optional.of("密码至少包含小写字母、大写字母、数字、特殊字符中的 3 类");
        }
        return Optional.empty();
    }

    private static boolean isDisallowedWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0x0085
                || codePoint == 0xFEFF;
    }
}
