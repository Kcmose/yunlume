package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ConditionalOnExpression("'${spring.cache.type:simple}' != 'redis'")
class FilePortablePreviewStore implements PortablePreviewStore {
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path root;
    private final int slots;
    private final long maxBytes;

    @Autowired
    FilePortablePreviewStore(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC(), Path.of(System.getProperty("java.io.tmpdir"),
                "yunlume-import-previews", "stored"));
    }

    FilePortablePreviewStore(ObjectMapper mapper, Clock clock, Path root) {
        this(mapper, clock, root, MAX_PREVIEWS, MAX_RESERVED_BYTES);
    }

    FilePortablePreviewStore(ObjectMapper mapper, Clock clock, Path root, int slots, long maxBytes) {
        this.mapper = mapper;
        this.clock = clock;
        this.root = root.toAbsolutePath().normalize();
        this.slots = slots;
        this.maxBytes = maxBytes;
    }

    @Override
    public Entry reserve(String token, long userId, long bytes, Instant expiresAt) {
        long reservationBytes = PortablePreviewStore.reservationBytes(bytes);
        if (!expiresAt.isAfter(clock.instant())) throw PortablePreviewStore.missing();
        return locked(() -> {
            List<Entry> entries = liveEntries();
            if (entries.size() >= slots || entries.stream().mapToLong(Entry::reservedBytes).sum() > maxBytes - reservationBytes) {
                throw PortablePreviewStore.full();
            }
            for (int slot = 0; slot < slots; slot++) {
                final int candidate = slot;
                if (entries.stream().noneMatch(entry -> entry.slot() == candidate)) {
                    Path directory = directory(slot);
                    PortablePreviewWorkspace.privateDirectory(directory);
                    Entry entry = new Entry(slot, token, userId, bytes, null, null,
                            expiresAt.toEpochMilli(), false, null, clock.instant().plus(ACTIVE_TTL).toEpochMilli());
                    write(entry);
                    return entry;
                }
            }
            throw PortablePreviewStore.full();
        });
    }

    @Override
    public Entry publish(Entry reservation, String sha256, String revision, Path archive, Runnable releaseWorkspace) {
        return locked(() -> {
            Entry current = requireOwned(reservation);
            if (current.ready() || current.activeJobId() != null) {
                throw PortablePreviewStore.missing();
            }
            copyBounded(archive, directory(reservation.slot()).resolve("package.zip"), reservation.archiveBytes());
            releaseWorkspace.run();
            Entry published = reservation.published(sha256, revision, clock.instant());
            write(published);
            return published;
        });
    }

    @Override
    public Optional<Entry> find(String token, long userId) {
        return locked(() -> liveEntries().stream().filter(entry -> entry.token().equals(token)
                && entry.userId() == userId && entry.ready() && entry.activeJobId() == null
                && entry.expiresAtMillis() > clock.millis()).findFirst());
    }

    @Override
    public Entry activate(Entry preview, String jobId) {
        return locked(() -> {
            Entry current = requireOwned(preview);
            if (!current.ready() || current.activeJobId() != null || current.expiresAtMillis() <= clock.millis()) {
                throw PortablePreviewStore.missing();
            }
            Entry active = current.active(jobId, clock.instant());
            write(active);
            return active;
        });
    }

    @Override
    public void copyArchive(Entry preview, Path target) {
        locked(() -> {
            Entry current = requireOwned(preview);
            if (preview.activeJobId() == null || !preview.activeJobId().equals(current.activeJobId())) {
                throw PortablePreviewStore.missing();
            }
            copyBounded(directory(preview.slot()).resolve("package.zip"), target, preview.archiveBytes());
            return null;
        });
    }

    @Override
    public void renew(Entry preview) {
        locked(() -> {
            Entry current = requireOwned(preview);
            if (preview.activeJobId() == null || !preview.activeJobId().equals(current.activeJobId())) {
                throw PortablePreviewStore.missing();
            }
            write(current.active(preview.activeJobId(), clock.instant()));
            return null;
        });
    }

    @Override
    public void renewProcessing(Entry reservation) {
        locked(() -> {
            Entry current = requireOwned(reservation);
            if (current.ready() || current.activeJobId() != null) throw PortablePreviewStore.missing();
            write(current.processing(clock.instant()));
            return null;
        });
    }

    @Override
    public void release(Entry preview) {
        locked(() -> {
            Entry current = read(preview.slot());
            if (current != null && current.token().equals(preview.token())
                    && java.util.Objects.equals(current.activeJobId(), preview.activeJobId())) {
                deleteSlot(preview.slot());
            }
            return null;
        });
    }

    @Override
    public void cleanupExpired() { locked(() -> { liveEntries(); return null; }); }

    private List<Entry> liveEntries() throws IOException {
        List<Entry> result = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            Entry entry = read(slot);
            if (entry == null || entry.retainUntilMillis() <= clock.millis()) {
                deleteSlot(slot);
            } else result.add(entry);
        }
        return result;
    }

    private void deleteSlot(int slot) throws IOException {
        Path path = directory(slot);
        PortablePreviewWorkspace.deleteTree(path);
        // 清理失败时不把残留字节视为已释放，后续预留须失败关闭。
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("预检存储清理未完成");
    }

    private Entry requireOwned(Entry expected) throws IOException {
        Entry current = read(expected.slot());
        if (current == null || !current.token().equals(expected.token())
                || current.userId() != expected.userId() || current.retainUntilMillis() <= clock.millis()) {
            throw PortablePreviewStore.missing();
        }
        return current;
    }

    private Entry read(int slot) throws IOException {
        Path directory = directory(slot);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("预检slot不是普通目录");
        Path metadata = directory.resolve("preview.json");
        if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 4096) {
            throw new IOException("预检清单无效");
        }
        Entry entry = mapper.readValue(Files.readAllBytes(metadata), Entry.class);
        if (entry.slot() != slot || entry.token() == null || !entry.token().matches("[a-f0-9]{32}")) {
            throw new IOException("预检清单归属无效");
        }
        entry.reservedBytes();
        return entry;
    }

    private void write(Entry entry) throws IOException {
        Path temporary = directory(entry.slot()).resolve("metadata-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, mapper.writeValueAsBytes(entry), StandardOpenOption.CREATE_NEW);
            PortablePreviewWorkspace.privateFile(temporary);
            Files.move(temporary, directory(entry.slot()).resolve("preview.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path directory(int slot) {
        if (slot < 0 || slot >= slots) throw new IllegalArgumentException("Invalid preview slot");
        return root.resolve("slot-" + slot);
    }

    static void copyBounded(Path source, Path target, long expectedBytes) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("预检归档不是普通文件");
        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > expectedBytes) throw new IOException("预检归档大小变化");
                output.write(buffer, 0, read);
            }
            if (total != expectedBytes) throw new IOException("预检归档大小变化");
        }
        PortablePreviewWorkspace.privateFile(target);
    }

    private <T> T locked(IoAction<T> action) {
        ReentrantLock local = LOCAL_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        local.lock();
        try {
            PortablePreviewWorkspace.privateDirectory(root);
            Path lockPath = root.resolve("budget.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var ignored = channel.lock()) {
                PortablePreviewWorkspace.privateFile(lockPath);
                return action.run();
            }
        } catch (IOException failure) {
            BusinessException unavailable = new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "无法访问导入预检存储");
            unavailable.initCause(failure);
            throw unavailable;
        } finally { local.unlock(); }
    }

    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
}
