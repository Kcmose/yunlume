package com.example.nav.module.install.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
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
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisRealAclCleanupSafetyTest {
    // 继承真实测试和 BeforeEach/AfterEach，仅替换外部 URI 和启用条件。
    static class IsolatedFixture extends RedisRealAclIntegrationTest {
        @Override RedisURI adminUri() { return RedisURI.create("redis://127.0.0.1:6379"); }
    }

    @Test
    void failedBeforeEachNeverDeletesPreexistingDataInRealJunitLifecycle() {
        try (FakeRedis redis = new FakeRedis();
             MockedStatic<RealRedisTestGuard> environmentGuard = mockStatic(RealRedisTestGuard.class)) {
            redis.values.put("existing:business:canary", "must-survive");
            var selector = DiscoverySelectors.selectMethod(IsolatedFixture.class,
                    "provisionedAclAcceptsEvalAndEvalshaExactProductionProbeWithoutResidue");
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
            List<String> lifecycleFailures = new ArrayList<>();
            JupiterTestEngine engine = new JupiterTestEngine();
            TestDescriptor descriptor = engine.discover(request, UniqueId.forEngine(engine.getId()));
            engine.execute(new ExecutionRequest(descriptor, new EngineExecutionListener() {
                @Override public void executionFinished(TestDescriptor test, TestExecutionResult result) {
                    if (test.isTest()) results.add(result);
                    result.getThrowable().ifPresent(error -> lifecycleFailures.add(test.getDisplayName() + ": " + error));
                }
            }, parameters));
            assertEquals(1, results.size(), lifecycleFailures.toString());
            assertEquals(TestExecutionResult.Status.FAILED, results.get(0).getStatus());
            assertTrue(results.get(0).getThrowable().orElseThrow().getMessage().contains("dedicated and empty"));
            assertEquals(Map.of("existing:business:canary", "must-survive"), redis.values);
            verify(redis.commands, never()).keys(anyString());
            verify(redis.commands, never()).del(any(String[].class));
            verify(redis.commands, never()).eval(anyString(), any(), any(String[].class), any(String[].class));
        }
    }

    @Test
    void cleanupDeletesOnlyOwnedFixturesAndPreservesUnexpectedKeys() {
        try (FakeRedis redis = new FakeRedis()) {
            IsolatedFixture fixture = new IsolatedFixture();
            fixture.isolatedRedisStartsEmpty();
            fixture.seedOwnedKey(redis.commands, "owned-random-key", "owned-value");
            redis.values.put("unrelated:concurrent:canary", "must-survive");
            assertThrows(AssertionError.class, fixture::adminRemovesOnlySuiteResidueAndProvesEmpty);
            assertEquals(Map.of("unrelated:concurrent:canary", "must-survive"), redis.values);
        }
    }

    @Test
    void replacedFixtureIsPreservedAndCleanupFailsClosed() {
        try (FakeRedis redis = new FakeRedis()) {
            IsolatedFixture fixture = new IsolatedFixture();
            fixture.isolatedRedisStartsEmpty();
            fixture.seedOwnedKey(redis.commands, "owned-random-key", "owned-value");
            redis.values.put("owned-random-key", "replacement");
            assertThrows(AssertionError.class, fixture::adminRemovesOnlySuiteResidueAndProvesEmpty);
            assertEquals(Map.of("owned-random-key", "replacement"), redis.values);
        }
    }

    @Test
    void collisionCannotBeClaimedAsOwnedAndAbsentOwnedKeysAreSafe() {
        try (FakeRedis redis = new FakeRedis()) {
            IsolatedFixture fixture = new IsolatedFixture();
            fixture.isolatedRedisStartsEmpty();
            redis.values.put("collision", "foreign");
            assertThrows(AssertionError.class, () -> fixture.seedOwnedKey(redis.commands, "collision", "mine"));
            assertThrows(AssertionError.class, fixture::adminRemovesOnlySuiteResidueAndProvesEmpty);
            assertEquals(Map.of("collision", "foreign"), redis.values);
        }
        try (FakeRedis redis = new FakeRedis()) {
            IsolatedFixture fixture = new IsolatedFixture();
            fixture.isolatedRedisStartsEmpty();
            fixture.seedOwnedKey(redis.commands, "owned", "mine");
            redis.values.remove("owned");
            assertDoesNotThrow(fixture::adminRemovesOnlySuiteResidueAndProvesEmpty);
        }
    }

    private static class FakeRedis implements AutoCloseable {
        final Map<String, String> values = new LinkedHashMap<>();
        final RedisCommands<String, String> commands;
        final MockedStatic<RedisClient> clients;

        @SuppressWarnings("unchecked")
        FakeRedis() {
            RedisClient client = mock(RedisClient.class);
            StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
            commands = mock(RedisCommands.class);
            when(client.connect()).thenReturn(connection);
            when(connection.sync()).thenReturn(commands);
            when(commands.dbsize()).thenAnswer(call -> (long) values.size());
            when(commands.setnx(anyString(), anyString())).thenAnswer(call ->
                    values.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
            when(commands.eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), anyString()))
                    .thenAnswer(call -> {
                        String key = ((String[]) call.getArgument(2))[0];
                        String expected = call.getArgument(3);
                        String actual = values.get(key);
                        if (actual == null) return 0L;
                        if (!actual.equals(expected)) return -1L;
                        values.remove(key);
                        return 1L;
                    });
            clients = mockStatic(RedisClient.class);
            clients.when(() -> RedisClient.create(any(RedisURI.class))).thenReturn(client);
        }
        @Override public void close() { clients.close(); }
    }
}
