package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels;
import com.example.nav.module.install.service.RealRedisTestGuard;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static com.example.nav.module.datapackage.service.PortablePreviewStoreTest.token;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:redis_preview_transfer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ResourceLock("isolated-redis-acl")
class RedisPortablePreviewIntegrationTest {
    @Autowired PortablePackageWriter writer;
    @Autowired PortablePackageReader reader;
    @Autowired PortableDataSnapshotService snapshots;
    @Autowired PortableImportTransactionService transaction;
    @Autowired PortableImportCommitStore commits;
    @Autowired UserMapper users;
    @Autowired ObjectMapper mapper;
    @TempDir Path temporary;
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;

    @BeforeAll static void requireRedis() { RealRedisTestGuard.require("REDIS_ACL_HOST"); }

    @BeforeEach void connectToDedicatedRedis() {
        withAdmin(commands -> assertEquals(0L, commands.dbsize(), "preview tests require dedicated empty Redis"));
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                env("REDIS_ACL_HOST"), Integer.parseInt(System.getenv().getOrDefault("REDIS_ACL_PORT", "6379")));
        config.setUsername("nav_test");
        config.setPassword(env("REDIS_ACL_PASSWORD"));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterEach void cleanupDedicatedRedis() {
        if (factory != null) factory.destroy();
        withAdmin(commands -> {
            List<String> keys = commands.keys("nav:portable-import:*");
            if (!keys.isEmpty()) commands.del(keys.toArray(String[]::new));
            assertEquals(0L, commands.dbsize());
        });
    }

    @Test void actualZipPreviewOnNodeACanBeConfirmedOnNodeBAndQueriedAfterBothRestart() throws Exception {
        var auth = UsernamePasswordAuthenticationToken.authenticated("admin", "unused", List.of());
        var nodeA = service(temporary.resolve("node-a"));
        byte[] archive = writer.exportPackage().bytes();
        String token = nodeA.preview(new MockMultipartFile("file", "portable.zip", "application/zip", archive), auth).previewToken();
        assertNotNull(token);
        assertEquals(404, assertThrows(BusinessException.class, () -> nodeA.queryByPreviewToken(token, auth)).getStatus().value());
        try (var files = Files.walk(temporary.resolve("node-a"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().equals("package.zip")));
        }
        var nodeB = service(temporary.resolve("node-b"));
        String jobId = nodeB.confirm(token, auth).jobId();
        assertEquals(PortablePackageModels.JobStage.COMPLETED, nodeB.job(jobId, auth).stage());
        assertTrue(commits.findByJobId(jobId).isPresent(), "真实事务提交标记必须存在");
        var restarted = service(temporary.resolve("node-c"));
        assertEquals(jobId, restarted.queryByPreviewToken(token, auth).jobId());
        assertEquals(jobId, restarted.confirm(token, auth).jobId());
    }

    @Test void binaryChunksLargeUserIdAndEveryStaleOwnerOperationAreProtected() throws Exception {
        var clock = new PortablePreviewStoreTest.MutableClock();
        var first = new RedisPortablePreviewStore(redis, mapper, clock);
        byte[] bytes = new byte[PortablePreviewStore.CHUNK_BYTES * 2 + 17];
        new Random(42).nextBytes(bytes);
        Path source = Files.write(temporary.resolve("source.zip"), bytes);
        var initial = first.reserve(token(), Long.MAX_VALUE, bytes.length, clock.instant().plusSeconds(900));
        var preview = first.publish(initial, "sha", "revision", source);
        var second = new RedisPortablePreviewStore(redis, mapper, clock);
        assertTrue(second.find(preview.token(), Long.MAX_VALUE - 1).isEmpty());
        var found = second.find(preview.token(), Long.MAX_VALUE).orElseThrow();
        var active = second.activate(found, "job-a");
        first.release(preview);
        Path restored = temporary.resolve("restored.zip");
        second.copyArchive(active, restored);
        assertArrayEquals(bytes, Files.readAllBytes(restored));
        clock.advance(Duration.ofMinutes(16));
        second.cleanupExpired();
        second.renew(active);
        second.copyArchive(active, temporary.resolve("after-expiry.zip"));
        clock.advance(Duration.ofHours(25));
        var next = second.reserve(token(), 2, bytes.length, clock.instant().plusSeconds(900));
        assertEquals(active.slot(), next.slot());
        var replacement = second.publish(next, "new-sha", "new-revision", source);
        first.release(active);
        assertTrue(second.find(replacement.token(), 2).isPresent());
        assertThrows(BusinessException.class, () -> first.publish(initial, "old", "old", source));
        assertThrows(BusinessException.class, () -> first.renew(active));
        assertThrows(BusinessException.class, () -> first.copyArchive(active, temporary.resolve("stale.zip")));
    }

    @Test void concurrentServersCannotOverbookAndProcessingReservationOutlivesPreviewDeadline() throws Exception {
        var clock = new PortablePreviewStoreTest.MutableClock();
        var first = new RedisPortablePreviewStore(redis, mapper, clock);
        var second = new RedisPortablePreviewStore(redis, mapper, clock);
        var pool = Executors.newFixedThreadPool(8);
        List<PortablePreviewStore.Entry> accepted = new ArrayList<>();
        try {
            var requests = new ArrayList<java.util.concurrent.Callable<PortablePreviewStore.Entry>>();
            for (int index = 0; index < 12; index++) {
                var node = index % 2 == 0 ? first : second;
                requests.add(() -> {
                    try { return node.reserve(token(), 1, PortablePackageModels.MAX_ARCHIVE_BYTES, clock.instant().plusSeconds(900)); }
                    catch (BusinessException full) { assertEquals(429, full.getStatus().value()); return null; }
                });
            }
            for (var future : pool.invokeAll(requests)) {
                var entry = future.get();
                if (entry != null) accepted.add(entry);
            }
            assertEquals(2, accepted.size());
        } finally { pool.shutdownNow(); }
        clock.advance(Duration.ofMinutes(16));
        second.cleanupExpired();
        accepted.forEach(first::renewProcessing);
        assertEquals(429, assertThrows(BusinessException.class, () -> second.reserve(
                token(), 1, PortablePackageModels.MAX_ARCHIVE_BYTES, clock.instant().plusSeconds(900))).getStatus().value());
        accepted.forEach(first::release);
        assertNotNull(second.reserve(token(), 1, PortablePackageModels.MAX_ARCHIVE_BYTES, clock.instant().plusSeconds(900)));
    }

    private PortableDataPackageService service(Path root) {
        return new PortableDataPackageService(writer, reader, snapshots, transaction, users, mapper,
                new SyncTaskExecutor(), new RedisPortableImportJobStore(redis, mapper), commits,
                Clock.systemUTC(), root, new RedisPortablePreviewStore(redis, mapper));
    }
    private void withAdmin(java.util.function.Consumer<io.lettuce.core.api.sync.RedisCommands<String, String>> action) {
        RedisURI uri = RedisURI.Builder.redis(env("REDIS_ACL_HOST"),
                        Integer.parseInt(System.getenv().getOrDefault("REDIS_ACL_PORT", "6379")))
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
}
