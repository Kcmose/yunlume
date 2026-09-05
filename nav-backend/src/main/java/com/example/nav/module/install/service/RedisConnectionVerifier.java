package com.example.nav.module.install.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.service.RedisPortableImportScripts;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Opens an isolated candidate connection and proves the exact Redis 7 runtime contract atomically. */
@Component
public class RedisConnectionVerifier {

    private static final SecureRandom PROBE_RANDOM = new SecureRandom();
    private static final Set<String> REQUIRED_ACL_COMMANDS = Set.of(
            "ping", "select", "info", "set", "get", "del", "eval", "evalsha",
            "exists", "incr", "psetex", "pexpire");

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
                verifyRuntimeCommands(connection.sync(), spec.database(), null);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            BusinessException unavailable = new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法连接目标 Redis；请检查地址、账号、TLS 与防火墙配置");
            unavailable.initCause(exception);
            throw unavailable;
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
        verifyRuntimeCommands(commands, 0, null);
    }

    static Set<String> requiredAclCommands() {
        return REQUIRED_ACL_COMMANDS;
    }

    void verifyRuntimeCommands(RedisCommands<String, String> commands, int database, String fixedValue) {
        if (!"PONG".equalsIgnoreCase(commands.ping())) {
            throw new IllegalStateException("Redis ping probe failed");
        }
        verifyServerVersion(commands.info("server"));
        if (!"OK".equalsIgnoreCase(commands.select(database))) {
            throw new IllegalStateException("Redis database selection probe failed");
        }

        String namespace = randomHex128();
        String owner = fixedValue == null ? "verify-" + randomHex128() : fixedValue;
        executeProductionScriptProbes(commands, namespace, owner, false);
        executeProductionScriptProbes(commands, namespace, owner, true);
    }

    void executeProductionScriptProbes(
            RedisCommands<String, String> commands,
            String suffix,
            String owner,
            boolean cached
    ) {
        String[] keys = productionProbeKeys(suffix);
        String source = RedisPortableImportScripts.atomicProbeSource();
        Object raw = cached
                ? commands.evalsha(sha1(source), ScriptOutputType.MULTI, keys,
                        owner, "probe-" + suffix, "json-" + owner, "failed-" + owner, "60000")
                : commands.eval(source, ScriptOutputType.MULTI, keys,
                        owner, "probe-" + suffix, "json-" + owner, "failed-" + owner, "60000");
        if (!(raw instanceof List<?> values)) {
            throw new IllegalStateException("Redis production script probe returned an invalid result");
        }
        List<String> actual = new ArrayList<>(values.size());
        for (Object value : values) actual.add(String.valueOf(value));
        if (!RedisPortableImportScripts.expectedProbeResult().equals(actual)) {
            throw new IllegalStateException("Redis production script probe returned unexpected branch results");
        }
    }

    static String[] productionProbeKeys(String suffix) {
        return new String[]{
                "nav:public-cache-version:probe-" + suffix,
                "nav:portable-import:preview:" + suffix,
                "nav:portable-import:job:" + suffix,
                "nav:portable-import:current:" + suffix,
                "nav:portable-import:lock:probe-" + suffix,
                "nav:portable-import:fence-sequence:probe-" + suffix,
                "nav:portable-import:recover-job:" + suffix,
                "nav:cache::probe-" + suffix + "::1"
        };
    }

    void verifyServerVersion(String serverInfo) {
        if (serverInfo == null) throw new IllegalStateException("Redis server version is unavailable");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^redis_version:(\\d+)(?:\\.\\d+){1,2}\\r?$")
                .matcher(serverInfo);
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 7) {
            throw new IllegalStateException("Redis 7 or newer is required");
        }
    }

    private String randomHex128() {
        byte[] random = new byte[16];
        PROBE_RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private String sha1(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-1 unavailable", exception);
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
