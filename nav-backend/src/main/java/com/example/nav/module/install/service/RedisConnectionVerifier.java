package com.example.nav.module.install.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;

/** Opens an isolated candidate connection and proves write, read and delete permissions. */
@Component
public class RedisConnectionVerifier {

    private static final SecureRandom PROBE_RANDOM = new SecureRandom();

    public void verifyReadWrite(RedisConnectionSpec spec) {
        Path temporaryCa = null;
        RedisClient client = null;
        try {
            if (spec.tlsMode() == RedisTlsMode.CUSTOM_CA) {
                temporaryCa = createTemporaryCa(spec.caCertificatePem());
            }
            String connectionHost = spec.tlsMode() == RedisTlsMode.DISABLED
                    ? spec.resolvedAddresses().get(0)
                    : spec.host();
            RedisURI.Builder uri = RedisURI.Builder.redis(connectionHost, spec.port())
                    .withDatabase(spec.database())
                    .withTimeout(spec.readTimeout())
                    .withSsl(spec.tlsMode() != RedisTlsMode.DISABLED)
                    .withVerifyPeer(spec.tlsMode() == RedisTlsMode.DISABLED
                            ? SslVerifyMode.NONE : SslVerifyMode.FULL);
            if (spec.username().isEmpty()) {
                uri.withPassword(spec.password().toCharArray());
            } else {
                uri.withAuthentication(spec.username(), spec.password().toCharArray());
            }
            client = RedisClient.create(uri.build());
            ClientOptions.Builder options = ClientOptions.builder()
                    .autoReconnect(false)
                    .pingBeforeActivateConnection(true)
                    .socketOptions(SocketOptions.builder()
                            .connectTimeout(spec.connectTimeout())
                            .keepAlive(true)
                            .build());
            if (temporaryCa != null) {
                options.sslOptions(SslOptions.builder()
                        .jdkSslProvider()
                        .trustManager(temporaryCa.toFile())
                        .handshakeTimeout(spec.connectTimeout())
                        .build());
            }
            client.setOptions(options.build());
            client.setDefaultTimeout(spec.readTimeout());
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                verifyReadWriteCommands(connection.sync());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法连接目标 Redis；请检查地址、账号、TLS 与防火墙配置");
        } finally {
            if (client != null) {
                try {
                    client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
                } catch (RuntimeException ignored) {
                    // Candidate connection cleanup never exposes endpoint details.
                }
            }
            deleteTemporaryCa(temporaryCa);
        }
    }

    void verifyReadWriteCommands(RedisCommands<String, String> commands) {
        byte[] random = new byte[16];
        PROBE_RANDOM.nextBytes(random);
        String suffix = HexFormat.of().formatHex(random);
        String key = "nav:install:probe:" + suffix;
        String value = "verify-" + suffix;
        boolean ownsKey = false;
        try {
            String saved = commands.set(key, value, SetArgs.Builder.nx().px(60_000));
            ownsKey = "OK".equalsIgnoreCase(saved);
            if (!ownsKey || !value.equals(commands.get(key)) || commands.del(key) != 1L) {
                throw new IllegalStateException("Redis read/write probe failed");
            }
            ownsKey = false;
        } finally {
            if (ownsKey) {
                try {
                    if (value.equals(commands.get(key))) commands.del(key);
                } catch (RuntimeException ignored) {
                    // The short TTL bounds cleanup if explicit deletion is unavailable.
                }
            }
        }
    }

    private Path createTemporaryCa(String pem) throws IOException {
        Path path = Files.createTempFile("nav-install-redis-ca-", ".pem");
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Random owner-scoped temporary file; it is removed immediately.
        }
        Files.writeString(path, pem, StandardCharsets.US_ASCII);
        return path;
    }

    private void deleteTemporaryCa(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Never log certificate-adjacent temporary paths.
        }
    }
}
