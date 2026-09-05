package com.example.nav.module.datapackage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 每份工作目录持有内核文件锁；重启后的清理只接管已到期且无人持锁的目录。 */
final class PortablePreviewWorkspace implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PortablePreviewWorkspace.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANIFEST = "workspace.json";
    private static final ConcurrentHashMap<Path, ReentrantLock> ROOT_LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private PortablePreviewWorkspace(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
    }

    static PortablePreviewWorkspace create(Path root, Clock clock) throws IOException {
        Path directory = root.resolve("work-" + UUID.randomUUID());
        try {
            return lockedRoot(root, () -> createLocked(root, directory, clock));
        } catch (IOException | RuntimeException failure) {
            throw new CreationException(Files.exists(directory, LinkOption.NOFOLLOW_LINKS), failure);
        }
    }

    private static PortablePreviewWorkspace createLocked(Path root, Path directory, Clock clock) throws IOException {
        privateDirectory(root);
        privateDirectory(directory);
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            lock = channel.lock();
            privateFile(directory.resolve("owner.lock"));
            Files.write(directory.resolve(MANIFEST), JSON.writeValueAsBytes(new Manifest(
                    1, directory.getFileName().toString(), clock.instant().plus(PortablePreviewStore.PREVIEW_TTL).toEpochMilli())),
                    StandardOpenOption.CREATE_NEW);
            privateFile(directory.resolve(MANIFEST));
            return new PortablePreviewWorkspace(directory, channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (lock != null) lock.close();
            if (channel != null) channel.close();
            deleteTree(directory);
            throw failure;
        }
    }

    Path directory() { return directory; }
    Path archive() { return directory.resolve("package.zip"); }

    static void reap(Path root, Clock clock) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return;
        try {
            lockedRoot(root, () -> { reapLocked(root, clock); return null; });
        } catch (IOException unavailable) {
            log.warn("Cannot lock portable preview workspace root");
        }
    }

    private static void reapLocked(Path root, Clock clock) {
        try (var children = Files.list(root)) {
            for (Path directory : children.toList()) {
                if (!directory.getFileName().toString().matches("work-[0-9a-f-]{36}")
                        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                Path manifest = directory.resolve(MANIFEST);
                Path owner = directory.resolve("owner.lock");
                // root锁排除仍在创建中的目录；无owner的本版目录是创建过程中崩溃留下的。
                if (!Files.exists(owner, LinkOption.NOFOLLOW_LINKS)) {
                    if (expiredDirectory(directory, clock)) deleteTree(directory);
                    continue;
                }
                if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) continue;
                try (FileChannel channel = FileChannel.open(owner, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    FileLock lease;
                    try { lease = channel.tryLock(); }
                    catch (OverlappingFileLockException activeHere) { continue; }
                    if (lease == null) continue;
                    try (lease) {
                        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                            if (expiredDirectory(directory, clock)) deleteTree(directory);
                        } else if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                                && Files.size(manifest) <= 2048) {
                            try {
                                Manifest value = JSON.readValue(Files.readAllBytes(manifest), Manifest.class);
                                if (value.format() == 1 && directory.getFileName().toString().equals(value.directory())
                                        && value.expiresAtMillis() <= clock.millis()) deleteTree(directory);
                            } catch (IOException interruptedManifestWrite) {
                                if (expiredDirectory(directory, clock)) deleteTree(directory);
                            }
                        }
                    }
                } catch (IOException invalidOrChangingDirectory) {
                    log.warn("Cannot inspect portable preview workspace {}", directory.getFileName());
                }
            }
        } catch (IOException unavailable) {
            log.warn("Cannot scan portable preview workspaces");
        }
    }

    private static boolean expiredDirectory(Path directory, Clock clock) throws IOException {
        return Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant()
                .plus(PortablePreviewStore.PREVIEW_TTL).isBefore(clock.instant());
    }

    private static <T> T lockedRoot(Path root, IoAction<T> action) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        ReentrantLock local = ROOT_LOCKS.computeIfAbsent(normalized, ignored -> new ReentrantLock());
        local.lock();
        try {
            privateDirectory(normalized);
            Path lockPath = normalized.resolve("root.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
                privateFile(lockPath);
                return action.run();
            }
        } finally { local.unlock(); }
    }

    static void privateDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("预检目录路径不能包含符号链接");
        }
        Files.createDirectories(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("预检目录不能是符号链接");
        }
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
    }

    static void privateFile(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    static void deleteTree(Path directory) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) {
            log.warn("Cannot remove portable preview workspace {}", directory.getFileName());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            // 保持锁直到目录内容删除完成，reaper不得在清理途中接管。
            deleteTree(directory);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("预检工作目录尚未清理，不能发布可确认预检");
            }
        } finally {
            try { lock.close(); } catch (IOException failure) { log.warn("Cannot release preview workspace lock"); }
            try { channel.close(); } catch (IOException failure) { log.warn("Cannot close preview workspace lock"); }
            closed = true;
        }
    }

    private record Manifest(int format, String directory, long expiresAtMillis) {}
    static final class CreationException extends IOException {
        private final boolean residue;
        private CreationException(boolean residue, Exception cause) {
            super("无法创建预检工作目录", cause);
            this.residue = residue;
        }
        boolean hasResidue() { return residue; }
    }
    @FunctionalInterface private interface IoAction<T> { T run() throws IOException; }
}
