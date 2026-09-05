package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class PortablePreviewStoreTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final MutableClock clock = new MutableClock();

    @Test
    void restartAndSecondInstanceCanReadArchiveWithoutFirstWorkspace() throws Exception {
        Path root = temporary.resolve("store");
        var first = store(root, 8, PortablePreviewStore.MAX_RESERVED_BYTES);
        byte[] archive = new byte[]{0, 12, -1, 45};
        Path source = Files.write(temporary.resolve("upload.zip"), archive);
        var reserved = first.reserve(token(), Long.MAX_VALUE, archive.length, clock.instant().plusSeconds(900));
        var published = first.publish(reserved, "sha", "revision", source);
        Files.delete(source);

        var restarted = store(root, 8, PortablePreviewStore.MAX_RESERVED_BYTES);
        assertTrue(restarted.find(published.token(), 1).isEmpty());
        var found = restarted.find(published.token(), Long.MAX_VALUE).orElseThrow();
        assertEquals("revision", found.businessRevision());
        var active = restarted.activate(found, "job");
        first.release(published);
        Path copy = temporary.resolve("copy.zip");
        restarted.copyArchive(active, copy);
        assertArrayEquals(archive, Files.readAllBytes(copy));
        clock.advance(Duration.ofMinutes(16));
        first.cleanupExpired();
        restarted.renew(active);
        restarted.copyArchive(active, temporary.resolve("after-expiry.zip"));
        first.release(active);
        assertTrue(restarted.find(published.token(), Long.MAX_VALUE).isEmpty());
    }

    @Test
    void oldOwnerCannotPublishReadRenewOrReleaseReassignedSlot() throws Exception {
        var first = store(temporary.resolve("store"), 1, Long.MAX_VALUE);
        var stale = first.reserve(token(), 1, 1, clock.instant().plusSeconds(1));
        clock.advance(Duration.ofHours(25));
        var replacement = first.reserve(token(), 2, 1, clock.instant().plusSeconds(900));
        Path source = Files.write(temporary.resolve("archive"), new byte[]{7});
        var published = first.publish(replacement, "sha", "rev", source);
        first.release(stale);
        assertEquals(published.token(), first.find(published.token(), 2).orElseThrow().token());
        assertThrows(BusinessException.class, () -> first.publish(stale, "old", "old", source));
        assertThrows(BusinessException.class, () -> first.activate(stale, "old"));
        assertThrows(BusinessException.class, () -> first.renew(stale.active("old", clock.instant())));
        assertThrows(BusinessException.class, () -> first.copyArchive(stale.active("old", clock.instant()), temporary.resolve("bad")));
    }

    @Test
    void weightedByteBudgetAndSlotLimitAreAtomicAcrossInstances() throws Exception {
        Path root = temporary.resolve("store");
        long cost = PortablePreviewStore.reservationBytes(1024);
        var first = store(root, 8, cost * 2);
        var second = store(root, 8, cost * 2);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 8; i++) {
                var selected = i % 2 == 0 ? first : second;
                calls.add(() -> {
                    try { selected.reserve(token(), 1, 1024, clock.instant().plusSeconds(900)); return true; }
                    catch (BusinessException full) { assertEquals(429, full.getStatus().value()); return false; }
                });
            }
            int accepted = 0;
            for (var result : pool.invokeAll(calls)) if (result.get()) accepted++;
            assertEquals(2, accepted);
        } finally { pool.shutdownNow(); }
        clock.advance(Duration.ofHours(25));
        assertNotNull(first.reserve(token(), 1, 1024, clock.instant().plusSeconds(900)));
        var one = store(temporary.resolve("one"), 1, Long.MAX_VALUE);
        one.reserve(token(), 1, 1, clock.instant().plusSeconds(900));
        assertEquals(429, assertThrows(BusinessException.class,
                () -> one.reserve(token(), 1, 1, clock.instant().plusSeconds(900))).getStatus().value());
    }

    @Test
    void interruptedPublishCanReleaseOnlyItsOwnSpace() throws Exception {
        var store = store(temporary.resolve("store"), 1, Long.MAX_VALUE);
        var entry = store.reserve(token(), 1, 3, clock.instant().plusSeconds(900));
        Path tooShort = Files.write(temporary.resolve("short"), new byte[]{1});
        assertThrows(BusinessException.class, () -> store.publish(entry, "sha", "revision", tooShort));
        store.release(entry);
        assertNotNull(store.reserve(token(), 1, 3, clock.instant().plusSeconds(900)));
    }

    @Test
    void reaperSkipsLiveLocksAndRecoversEveryCreationCrashStageWithoutFollowingLinks() throws Exception {
        Path root = temporary.resolve("work");
        try (var live = PortablePreviewWorkspace.create(root, clock)) {
            Path outside = Files.createDirectories(temporary.resolve("outside"));
            Path sentinel = Files.writeString(outside.resolve("keep"), "keep");
            Path missingOwner = Files.createDirectory(root.resolve("work-" + UUID.randomUUID()));
            Files.createSymbolicLink(missingOwner.resolve("escape"), outside);
            Path missingManifest = Files.createDirectory(root.resolve("work-" + UUID.randomUUID()));
            Files.createFile(missingManifest.resolve("owner.lock"));
            Path partialManifest = Files.createDirectory(root.resolve("work-" + UUID.randomUUID()));
            Files.createFile(partialManifest.resolve("owner.lock"));
            Files.writeString(partialManifest.resolve("workspace.json"), "{");
            for (Path path : java.util.List.of(missingOwner, missingManifest, partialManifest)) {
                Files.setLastModifiedTime(path, FileTime.from(clock.instant()));
            }
            clock.advance(Duration.ofMinutes(16));
            PortablePreviewWorkspace.reap(root, clock);
            assertTrue(Files.exists(live.directory()));
            assertFalse(Files.exists(missingOwner));
            assertFalse(Files.exists(missingManifest));
            assertFalse(Files.exists(partialManifest));
            assertEquals("keep", Files.readString(sentinel));
        }
    }

    private FilePortablePreviewStore store(Path root, int slots, long bytes) {
        return new FilePortablePreviewStore(mapper, clock, root, slots, bytes);
    }
    static String token() { return UUID.randomUUID().toString().replace("-", ""); }
    static final class MutableClock extends Clock {
        private Instant now = Instant.now();
        void advance(Duration value) { now = now.plus(value); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
