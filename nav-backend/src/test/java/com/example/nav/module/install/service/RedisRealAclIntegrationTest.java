package com.example.nav.module.install.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.service.RedisPortableImportScripts;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("isolated-redis-acl")
class RedisRealAclIntegrationTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeAll
    static void requireRealRedis() {
        RealRedisTestGuard.require("REDIS_ACL_HOST");
    }

    @BeforeEach
    void isolatedRedisStartsEmpty() {
        assertEquals(0L, adminDbSize(), "Redis ACL integration service must be dedicated and empty");
    }

    @AfterEach
    void adminRemovesOnlySuiteResidueAndProvesEmpty() {
        RedisClient client = RedisClient.create(adminUri());
        try (var connection = client.connect()) {
            List<String> keys = connection.sync().keys("*");
            if (!keys.isEmpty()) connection.sync().del(keys.toArray(String[]::new));
            assertEquals(0L, connection.sync().dbsize());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void provisionedAclAcceptsEvalAndEvalshaExactProductionProbeWithoutResidue() {
        new RedisConnectionVerifier().verifyReadWrite(spec("nav_test", env("REDIS_ACL_PASSWORD")));
        assertEquals(0L, adminDbSize());
    }

    @Test
    void commandDeniedAfterWouldBeEarlyWritesIsRejectedByPreflightAndExactOriginalsSurvive() {
        String suffix = random128();
        String[] keys = RedisConnectionVerifier.productionProbeKeys(suffix);
        RedisClient admin = RedisClient.create(adminUri());
        RedisClient narrow = RedisClient.create(userUri("nav_narrow", "REDIS_ACL_NARROW_PASSWORD"));
        try (var adminConnection = admin.connect(); var narrowConnection = narrow.connect()) {
            adminConnection.sync().set(keys[2], "original-job-" + suffix);
            adminConnection.sync().set(keys[3], "original-current-" + suffix);
            adminConnection.sync().set(keys[5], "0");

            assertThrows(RuntimeException.class, () -> new RedisConnectionVerifier()
                    .executeProductionScriptProbes(narrowConnection.sync(), suffix,
                            "owner-" + random128(), false));

            assertEquals("original-job-" + suffix, adminConnection.sync().get(keys[2]));
            assertEquals("original-current-" + suffix, adminConnection.sync().get(keys[3]));
            assertEquals("0", adminConnection.sync().get(keys[5]));
            assertEquals(3L, adminConnection.sync().dbsize());
        } finally {
            narrow.shutdown();
            admin.shutdown();
        }
    }

    @Test
    void readOnlyAndWriteOnlyUsersAreRejectedWithoutResidue() {
        assertThrows(BusinessException.class, () -> new RedisConnectionVerifier().verifyReadWrite(
                spec("nav_readonly", env("REDIS_ACL_READONLY_PASSWORD"))));
        assertEquals(0L, adminDbSize());
        assertThrows(BusinessException.class, () -> new RedisConnectionVerifier().verifyReadWrite(
                spec("nav_writeonly", env("REDIS_ACL_WRITEONLY_PASSWORD"))));
        assertEquals(0L, adminDbSize());
    }

    @Test
    void everyExactBoundaryCollisionIncludingZeroFenceSurvivesUnchanged() {
        String suffix = random128();
        String[] keys = RedisConnectionVerifier.productionProbeKeys(suffix);
        RedisClient admin = RedisClient.create(adminUri());
        RedisClient runtime = RedisClient.create(userUri("nav_test", "REDIS_ACL_PASSWORD"));
        try (var adminConnection = admin.connect(); var runtimeConnection = runtime.connect()) {
            for (String key : keys) {
                assertEquals(0L, adminConnection.sync().dbsize());
                String original = key.endsWith("fence-sequence") ? "0" : "original-" + random128();
                adminConnection.sync().set(key, original);

                assertThrows(RuntimeException.class, () -> new RedisConnectionVerifier()
                        .executeProductionScriptProbes(runtimeConnection.sync(), suffix,
                                "owner-" + random128(), false), key);

                assertEquals(original, adminConnection.sync().get(key), key);
                assertEquals(1L, adminConnection.sync().dbsize(), key);
                adminConnection.sync().del(key);
            }
        } finally {
            runtime.shutdown();
            admin.shutdown();
        }
    }

    @Test
    void atomicProbeReturnsEveryNamedExactBranchResultAndLeavesNoKeys() {
        String suffix = random128();
        String owner = "owner-" + random128();
        String[] keys = RedisConnectionVerifier.productionProbeKeys(suffix);
        RedisClient client = RedisClient.create(userUri("nav_test", "REDIS_ACL_PASSWORD"));
        try (var connection = client.connect()) {
            Object raw = connection.sync().eval(RedisPortableImportScripts.atomicProbeSource(),
                    ScriptOutputType.MULTI, keys, owner, "probe-" + suffix,
                    "json-" + owner, "failed-" + owner, "60000");
            List<String> actual = new ArrayList<>();
            assertTrue(raw instanceof List<?>);
            for (Object value : (List<?>) raw) actual.add(String.valueOf(value));
            assertEquals(RedisPortableImportScripts.expectedProbeResult(), actual);
            assertEquals(0L, adminDbSize());
        } finally {
            client.shutdown();
        }
    }

    private RedisConnectionSpec spec(String username, String password) {
        String host = env("REDIS_ACL_HOST");
        return new RedisConnectionSpec(host, port(), username, password, 0,
                RedisTlsMode.DISABLED, null, Duration.ofSeconds(3), Duration.ofSeconds(3), List.of(host));
    }

    private RedisURI userUri(String username, String passwordName) {
        return RedisURI.Builder.redis(env("REDIS_ACL_HOST"), port())
                .withAuthentication(username, env(passwordName).toCharArray()).build();
    }

    private RedisURI adminUri() {
        return RedisURI.Builder.redis(env("REDIS_ACL_HOST"), port())
                .withAuthentication(env("REDIS_ACL_ADMIN_USERNAME"),
                        env("REDIS_ACL_ADMIN_PASSWORD").toCharArray()).build();
    }

    private long adminDbSize() {
        RedisClient client = RedisClient.create(adminUri());
        try (var connection = client.connect()) {
            return connection.sync().dbsize();
        } finally {
            client.shutdown();
        }
    }

    private String random128() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private int port() {
        return Integer.parseInt(System.getenv().getOrDefault("REDIS_ACL_PORT", "6379"));
    }

    private String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
