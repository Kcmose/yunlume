package com.example.nav.module.publicdata;

import com.example.nav.module.install.service.RealRedisTestGuard;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.List;
import java.security.SecureRandom;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@ResourceLock("isolated-redis-acl")
class PublicDataCacheRecoveryIntegrationTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeAll
    static void requireRealRedis() {
        RealRedisTestGuard.require("REDIS_CACHE_RECOVERY_HOST");
    }

    @BeforeEach
    void isolatedRedisStartsEmpty() {
        assertEquals(0L, adminDbSize(), "cache recovery Redis must be dedicated and empty");
    }

    @AfterEach
    void adminCleansExactDedicatedServiceAndVerifiesNoResidue() {
        RedisClient client = RedisClient.create(adminUri());
        try (var connection = client.connect()) {
            List<String> keys = connection.sync().keys("*");
            if (!keys.isEmpty()) connection.sync().del(keys.toArray(String[]::new));
            assertEquals(0L, connection.sync().dbsize());
        } finally {
            client.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.9", "1e2", "+12", "-1", " 12", "012", "2147483648"})
    void malformedOrOutOfRangeRedisGenerationFailsClosedWithoutMutation(String malformed) {
        String cacheName = "recovery-" + random128();
        JdbcTemplate jdbc = database(12);
        LettuceConnectionFactory factory = connectionFactory(port());
        String versionKey = "nav:public-cache-version:" + cacheName;
        adminSet(versionKey, malformed);
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            PublicDataCacheVersion version = version(redis, jdbc);

            assertThrows(RuntimeException.class, () -> version.current(cacheName));
            assertEquals(malformed, adminGet(versionKey));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void redisAheadChoosesStrictlyGreaterGenerationThenPersistsItDurably() {
        String cacheName = "ahead-" + random128();
        JdbcTemplate jdbc = database(12);
        LettuceConnectionFactory factory = connectionFactory(port());
        String versionKey = "nav:public-cache-version:" + cacheName;
        adminSet(versionKey, "19");
        try {
            PublicDataCacheGenerationStore store = new PublicDataCacheGenerationStore(jdbc);
            PublicDataCacheVersion version = version(new StringRedisTemplate(factory), jdbc);

            assertEquals("20", version.current(cacheName));
            assertEquals(20L, store.current());
            assertEquals("20", adminGet(versionKey));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void redisAheadAtIntegerMaximumFailsBeforeMutation() {
        String cacheName = "overflow-" + random128();
        JdbcTemplate jdbc = database(12);
        LettuceConnectionFactory factory = connectionFactory(port());
        String versionKey = "nav:public-cache-version:" + cacheName;
        adminSet(versionKey, "2147483647");
        try {
            PublicDataCacheVersion version = version(new StringRedisTemplate(factory), jdbc);

            assertThrows(RuntimeException.class, () -> version.current(cacheName));
            assertEquals("2147483647", adminGet(versionKey));
            assertEquals(12L, new PublicDataCacheGenerationStore(jdbc).current());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void durableGenerationRepairsRedisAfterOutageAndProcessReconstruction() {
        String cacheName = "outage-" + random128();
        JdbcTemplate jdbc = database(1);
        LettuceConnectionFactory recovered = connectionFactory(port());
        String versionKey = "nav:public-cache-version:" + cacheName;
        adminSet(versionKey, "0");
        try {
            PublicDataCacheVersion reconstructed = version(new StringRedisTemplate(recovered), jdbc);

            assertEquals("1", reconstructed.current(cacheName));
            assertEquals("1", adminGet(versionKey));
        } finally {
            recovered.destroy();
        }
    }

    private JdbcTemplate database(long generation) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cache-" + random128() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER NOT NULL, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO site_config(id, version, updated_at) VALUES (1, ?, CURRENT_TIMESTAMP)", generation);
        return jdbc;
    }

    private PublicDataCacheVersion version(StringRedisTemplate redis, JdbcTemplate jdbc) {
        return new PublicDataCacheVersion(
                mock(RedisCacheManager.class), redis, new PublicDataCacheGenerationStore(jdbc),
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private LettuceConnectionFactory connectionFactory(int port) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                env("REDIS_CACHE_RECOVERY_HOST"), port);
        configuration.setUsername("nav_test");
        configuration.setPassword(env("REDIS_CACHE_RECOVERY_PASSWORD"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisURI adminUri() {
        return RedisURI.Builder.redis(env("REDIS_CACHE_RECOVERY_HOST"), port())
                .withAuthentication(env("REDIS_ACL_ADMIN_USERNAME"),
                        env("REDIS_ACL_ADMIN_PASSWORD").toCharArray()).build();
    }

    private void adminSet(String key, String value) {
        RedisClient client = RedisClient.create(adminUri());
        try (var connection = client.connect()) {
            connection.sync().set(key, value);
        } finally {
            client.shutdown();
        }
    }

    private String adminGet(String key) {
        RedisClient client = RedisClient.create(adminUri());
        try (var connection = client.connect()) {
            return connection.sync().get(key);
        } finally {
            client.shutdown();
        }
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
        return Integer.parseInt(System.getenv().getOrDefault("REDIS_CACHE_RECOVERY_PORT", "6379"));
    }

    private String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
