package com.example.nav.module.install.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 在 CI 的独占 Redis 中使用原测试生命周期复现误配置，不 mock Redis 或删除陌生键。 */
@ResourceLock("isolated-redis-acl")
class RedisTestCleanupSafetyIntegrationTest {
    @BeforeAll static void requireRedis() {
        RealRedisTestGuard.require("REDIS_ACL_HOST");
        RealRedisTestGuard.require("REDIS_CACHE_RECOVERY_HOST");
        assertEquals(env("REDIS_ACL_HOST"), env("REDIS_CACHE_RECOVERY_HOST"));
        assertEquals(port("REDIS_ACL_PORT"), port("REDIS_CACHE_RECOVERY_PORT"),
                "cleanup regression requires the same dedicated service as both original suites");
    }

    @BeforeEach void requireEmptyService() {
        withAdmin(commands -> assertEquals(0L, commands.dbsize(), "cleanup regression requires dedicated empty Redis"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example.nav.module.publicdata.PublicDataCacheRecoveryIntegrationTest#isolatedRedisStartsEmpty#adminCleansExactDedicatedServiceAndVerifiesNoResidue",
            "com.example.nav.module.datapackage.service.RedisPortablePreviewIntegrationTest#connectToDedicatedRedis#cleanupDedicatedRedis"
    })
    void originalFailedBeforeEachPreservesExistingKeys(String original) throws Exception {
        String[] names = original.split("#");
        var constructor = Class.forName(names[0]).getDeclaredConstructor();
        constructor.setAccessible(true);
        Object fixture = constructor.newInstance();
        String key = "nav:portable-import:preexisting:" + UUID.randomUUID();
        String value = "canary-" + UUID.randomUUID();
        withAdmin(commands -> {
            assertTrue(commands.setnx(key, value));
            try {
                assertOriginalLifecycleFailsBeforeBody(fixture, names[1], names[2]);
                assertEquals(value, commands.get(key), "failed setup must preserve preexisting data");
            } finally {
                removeExact(commands, key, value);
            }
            assertEquals(0L, commands.dbsize());
        });
    }

    @Test void cleanupRemovesOwnedFixturesAndPreservesConcurrentForeignKeys() {
        withAdmin(commands -> {
            var owned = new OwnedRedisTestKeys();
            owned.verifyEmpty(commands.dbsize());
            String ownKey = "nav:cleanup:owned:" + UUID.randomUUID();
            String foreignKey = "nav:cleanup:foreign:" + UUID.randomUUID();
            String ownValue = UUID.randomUUID().toString();
            String foreignValue = UUID.randomUUID().toString();
            owned.create(commands, ownKey, ownValue);
            assertTrue(commands.setnx(foreignKey, foreignValue));
            try {
                assertThrows(AssertionError.class, () -> owned.cleanup(commands));
                assertNull(commands.get(ownKey));
                assertEquals(foreignValue, commands.get(foreignKey));
            } finally {
                removeExact(commands, ownKey, ownValue);
                removeExact(commands, foreignKey, foreignValue);
            }
            assertEquals(0L, commands.dbsize());
        });
    }

    @Test void cleanupPreservesReplacedValuesAndCannotClaimCollidingKeys() {
        withAdmin(commands -> {
            var owned = new OwnedRedisTestKeys();
            owned.verifyEmpty(commands.dbsize());
            String key = "nav:cleanup:replaced:" + UUID.randomUUID();
            String initial = UUID.randomUUID().toString();
            String replacement = UUID.randomUUID().toString();
            owned.create(commands, key, initial);
            commands.set(key, replacement);
            try {
                assertThrows(AssertionError.class, () -> owned.create(commands, key, initial));
                assertThrows(AssertionError.class, () -> owned.cleanup(commands));
                assertEquals(replacement, commands.get(key));
            } finally {
                removeExact(commands, key, replacement);
                removeExact(commands, key, initial);
            }
            assertEquals(0L, commands.dbsize());
        });
    }

    // 组合原始 BeforeEach/AfterEach，避免给前置失败场景启动无关的 Spring 上下文。
    private static final ThreadLocal<LifecycleActions> ACTIONS = new ThreadLocal<>();
    public static class LifecycleFixture {
        @BeforeEach void setup() { ACTIONS.get().before.run(); }
        @AfterEach void cleanup() { ACTIONS.get().after.run(); }
        @Test void body() { ACTIONS.get().bodyReached = true; }
    }
    private static final class LifecycleActions {
        final Runnable before;
        final Runnable after;
        boolean bodyReached;
        LifecycleActions(Runnable before, Runnable after) { this.before = before; this.after = after; }
    }

    private static void assertOriginalLifecycleFailsBeforeBody(Object fixture, String before, String after) {
        var actions = new LifecycleActions(() -> invoke(fixture, before), () -> invoke(fixture, after));
        ACTIONS.set(actions);
        try {
            var selector = DiscoverySelectors.selectMethod(LifecycleFixture.class, "body");
            ConfigurationParameters parameters = new ConfigurationParameters() {
                public Optional<String> get(String key) { return Optional.empty(); }
                public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
                public int size() { return 0; }
                public Set<String> keySet() { return Set.of(); }
            };
            EngineDiscoveryRequest request = new EngineDiscoveryRequest() {
                public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type) {
                    return type.isInstance(selector) ? List.of(type.cast(selector)) : List.of();
                }
                public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type) { return List.of(); }
                public ConfigurationParameters getConfigurationParameters() { return parameters; }
            };
            List<TestExecutionResult> results = new ArrayList<>();
            var engine = new JupiterTestEngine();
            engine.execute(new ExecutionRequest(engine.discover(request, UniqueId.forEngine(engine.getId())),
                    new EngineExecutionListener() {
                        @Override public void executionFinished(TestDescriptor test, TestExecutionResult result) {
                            if (test.isTest()) results.add(result);
                        }
                    }, parameters));
            assertEquals(1, results.size());
            assertEquals(TestExecutionResult.Status.FAILED, results.get(0).getStatus());
            Throwable failure = results.get(0).getThrowable().orElseThrow();
            assertInstanceOf(AssertionError.class, failure);
            assertTrue(failure.getMessage().contains("dedicated and empty"));
            assertFalse(actions.bodyReached);
        } finally { ACTIONS.remove(); }
    }

    private static void invoke(Object fixture, String name) {
        try {
            var method = fixture.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(fixture);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof RuntimeException error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void removeExact(RedisCommands<String, String> commands, String key, String value) {
        commands.eval("if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
                ScriptOutputType.INTEGER, new String[]{key}, value);
    }
    private static void withAdmin(Consumer<RedisCommands<String, String>> action) {
        var uri = RedisURI.Builder.redis(env("REDIS_ACL_HOST"), port("REDIS_ACL_PORT"))
                .withAuthentication(env("REDIS_ACL_ADMIN_USERNAME"), env("REDIS_ACL_ADMIN_PASSWORD").toCharArray()).build();
        RedisClient client = RedisClient.create(uri);
        try (var connection = client.connect()) { action.accept(connection.sync()); }
        finally { client.shutdown(); }
    }
    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static int port(String name) { return Integer.parseInt(System.getenv().getOrDefault(name, "6379")); }
}
