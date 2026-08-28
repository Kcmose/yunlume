package com.example.nav.common.config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Computes a non-exported integrity digest for installer-managed Redis settings. */
public final class RedisConfigurationDigest {

    private RedisConfigurationDigest() {
    }

    public static String digest(
            String host,
            int port,
            String username,
            String password,
            int database,
            String tlsMode,
            String caCertificatePem,
            long connectTimeoutSeconds,
            long readTimeoutSeconds
    ) {
        return digest(host, port, username, password, database, tlsMode,
                caCertificatePem, connectTimeoutSeconds, readTimeoutSeconds, List.of());
    }

    public static String digest(
            String host,
            int port,
            String username,
            String password,
            int database,
            String tlsMode,
            String caCertificatePem,
            long connectTimeoutSeconds,
            long readTimeoutSeconds,
            List<String> resolvedAddresses
    ) {
        MessageDigest digest = sha256();
        add(digest, host);
        add(digest, Integer.toString(port));
        add(digest, username);
        add(digest, password);
        add(digest, Integer.toString(database));
        add(digest, tlsMode);
        add(digest, caCertificatePem);
        add(digest, Long.toString(connectTimeoutSeconds));
        add(digest, Long.toString(readTimeoutSeconds));
        add(digest, resolvedAddresses == null ? "" : String.join(",", resolvedAddresses));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String textDigest(String value) {
        MessageDigest digest = sha256();
        digest.update(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
