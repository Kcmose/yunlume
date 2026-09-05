package com.example.nav.module.install.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.service.RedisPortableImportScripts;
import com.example.nav.module.install.model.RedisConnectionSpec;
import com.example.nav.module.install.model.RedisTlsMode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("isolated-redis-acl")
class RedisRealAclIntegrationTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, String> ownedKeys = new LinkedHashMap<>();
    private boolean emptyDatabaseVerified;

    @BeforeAll
    static void requireRealRedis() {
        RealRedisTestGuard.require("REDIS_ACL_HOST");
    }

    @BeforeEach
    void isolatedRedisStartsEmpty() {
        assertEquals(0L, adminDbSize(), "Redis ACL integration service must be dedicated and empty");
        emptyDatabaseVerified = true;
    }

    @AfterEach
    void adminRemovesOnlySuiteResidueAndProvesEmpty() {
        // JUnit 在 BeforeEach 失败后仍执行 AfterEach；未经验证的数据库绝不能清理。
        if (!emptyDatabaseVerified) return;
        RedisClient client = RedisClient.create(adminUri());
        try (var connection = client.connect()) {
            for (var entry : ownedKeys.entrySet()) {
                removeOwnedKey(connection.sync(), entry.getKey(), entry.getValue());
            }
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
            seedOwnedKey(adminConnection.sync(), keys[2], "original-job-" + suffix);
            seedOwnedKey(adminConnection.sync(), keys[3], "original-current-" + suffix);
            seedOwnedKey(adminConnection.sync(), keys[5], "0");

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
                seedOwnedKey(adminConnection.sync(), key, original);

                assertThrows(RuntimeException.class, () -> new RedisConnectionVerifier()
                        .executeProductionScriptProbes(runtimeConnection.sync(), suffix,
                                "owner-" + random128(), false), key);

                assertEquals(original, adminConnection.sync().get(key), key);
                assertEquals(1L, adminConnection.sync().dbsize(), key);
                removeOwnedKey(adminConnection.sync(), key, original);
                ownedKeys.remove(key);
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

    RedisURI adminUri() {
        return RedisURI.Builder.redis(env("REDIS_ACL_HOST"), port())
                .withAuthentication(env("REDIS_ACL_ADMIN_USERNAME"),
                        env("REDIS_ACL_ADMIN_PASSWORD").toCharArray()).build();
    }

    void seedOwnedKey(RedisCommands<String, String> commands, String key, String value) {
        assertTrue(emptyDatabaseVerified, "database isolation must be verified before creating fixtures");
        // SETNX 不覆盖碰撞键，只有已确认创建的键才归当前测试所有。
        assertTrue(commands.setnx(key, value), "test fixture key already exists");
        ownedKeys.put(key, value);
    }

    private void removeOwnedKey(RedisCommands<String, String> commands, String key, String value) {
        // 原子核对值后删除，保留被其他参与者替换或测试异常改写的键供排查。
        Long removed = commands.eval("""
                local current = redis.call('GET', KEYS[1])
                if not current then return 0 end
                if current ~= ARGV[1] then return -1 end
                return redis.call('DEL', KEYS[1])
                """, ScriptOutputType.INTEGER, new String[]{key}, value);
        assertTrue(removed != null && (removed == 0 || removed == 1),
                "test fixture ownership changed; refusing cleanup");
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
