package com.example.nav.module.upload.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BackgroundImageStorageService {

    private static final Pattern GENERATED_FILENAME = ManagedBackgroundReferences.FILENAME;
    private static final Pattern TEMPORARY_FILENAME =
            Pattern.compile("^\\.upload-[A-Za-z0-9._-]+\\.tmp$");

    private final Path uploadRoot;
    private final Path backgroundDirectory;
    private final String managedUrlPrefix;
    private final ManagedBackgroundReferences backgroundReferences;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxFiles;
    private final long orphanGraceMs;
    private final SiteConfigMapper siteConfigMapper;
    private final Clock clock;
    private final ReentrantLock storageLock = new ReentrantLock();

    @Autowired
    public BackgroundImageStorageService(
            UploadStorageProperties properties,
            SiteConfigMapper siteConfigMapper
    ) {
        this(properties, siteConfigMapper, Clock.systemUTC());
    }

    BackgroundImageStorageService(
            UploadStorageProperties properties,
            SiteConfigMapper siteConfigMapper,
            Clock clock
    ) {
        if (properties == null || siteConfigMapper == null || clock == null) {
            throw new IllegalArgumentException("上传存储配置不能为空");
        }
        if (properties.getDirectory() == null || properties.getDirectory().isBlank()) {
            throw new IllegalArgumentException("上传目录不能为空");
        }
        if (properties.getMaxBytes() <= 0 || properties.getMaxTotalBytes() <= 0
                || properties.getMaxFiles() <= 0 || properties.getOrphanGraceMs() < 0) {
            throw new IllegalArgumentException("上传存储限制必须为正数，孤儿文件宽限期不能为负数");
        }
        if (properties.getMaxTotalBytes() < properties.getMaxBytes()) {
            throw new IllegalArgumentException("上传目录总容量不能小于单文件上限");
        }

        this.uploadRoot = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
        this.backgroundDirectory = uploadRoot.resolve("backgrounds").normalize();
        if (!backgroundDirectory.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("上传目录配置无效");
        }
        this.backgroundReferences = new ManagedBackgroundReferences(properties);
        this.managedUrlPrefix = backgroundReferences.urlPrefix();
        this.maxFileBytes = properties.getMaxBytes();
        this.maxTotalBytes = properties.getMaxTotalBytes();
        this.maxFiles = properties.getMaxFiles();
        this.orphanGraceMs = properties.getOrphanGraceMs();
        this.siteConfigMapper = siteConfigMapper;
        this.clock = clock;
    }

    @Transactional
    public StoredImage store(MultipartFile file, String filename) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的图片");
        }
        validateGeneratedFilename(filename);

        Set<String> referencedFiles = lockReferencedFilenames();
        storageLock.lock();
        Path temporary = null;
        try {
            Path safeDirectory = ensureSafeStorageDirectory();
            cleanupOrphansLocked(safeDirectory, referencedFiles);

            StorageInventory inventory = inspectInventory(safeDirectory);
            long declaredBytes = file.getSize();
            if (declaredBytes <= 0) {
                throw BusinessException.badRequest("上传图片不能为空");
            }
            if (declaredBytes > maxFileBytes) {
                throw fileTooLarge();
            }
            enforceQuota(inventory, declaredBytes);

            temporary = Files.createTempFile(safeDirectory, ".upload-", ".tmp");
            file.transferTo(temporary);
            long actualBytes = Files.size(temporary);
            if (actualBytes <= 0) {
                throw BusinessException.badRequest("上传图片不能为空");
            }
            if (actualBytes > maxFileBytes) {
                throw fileTooLarge();
            }
            enforceQuota(inventory, actualBytes);

            Path target = safeDirectory.resolve(filename).normalize();
            if (!target.startsWith(safeDirectory) || !safeDirectory.equals(target.getParent())) {
                throw BusinessException.badRequest("图片文件名无效");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(HttpStatus.CONFLICT, "图片文件名冲突，请重试");
            }

            makePubliclyReadable(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
            temporary = null;
            return new StoredImage(filename, actualBytes, managedUrlPrefix + filename);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            log.error("Failed to persist uploaded background image", exception);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    log.warn("Failed to remove temporary background upload {}", temporary.getFileName(), exception);
                }
            }
            storageLock.unlock();
        }
    }

    @Transactional
    public CleanupResult cleanupOrphans() {
        Set<String> referencedFiles;
        try {
            referencedFiles = lockReferencedFilenames();
        } catch (RuntimeException exception) {
            log.error("Failed to lock current background image references; no files were deleted", exception);
            return CleanupResult.skippedResult();
        }
        storageLock.lock();
        try {
            return cleanupOrphansLocked(ensureSafeStorageDirectory(), referencedFiles);
        } catch (IOException exception) {
            throw new IllegalStateException("无法清理背景图片存储目录", exception);
        } finally {
            storageLock.unlock();
        }
    }

    /**
     * Stores an already validated group of portable-package image assets as one
     * quota operation. The caller is responsible for validating image content;
     * this method is deliberately limited to safe paths, quotas and atomic file
     * publication. The site row lock is acquired before the instance storage
     * lock and remains held through the importing transaction's completion.
     */
    @Transactional
    public List<ImportedAsset> importValidatedAssets(List<ImportAssetSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Set<String> referencedFiles = lockReferencedFilenames();
        storageLock.lock();
        List<ImportedAsset> imported = new ArrayList<>();
        try {
            Path safeDirectory = ensureSafeStorageDirectory();
            cleanupOrphansLocked(safeDirectory, referencedFiles);

            StorageInventory inventory = inspectInventory(safeDirectory);
            long pendingBytes = 0;
            Set<String> keys = new HashSet<>();
            for (ImportAssetSource source : sources) {
                if (source == null || source.key() == null || source.key().isBlank() || !keys.add(source.key())) {
                    throw BusinessException.badRequest("导入图片标识无效或重复");
                }
                if (source.path() == null || Files.isSymbolicLink(source.path())) {
                    throw BusinessException.badRequest("导入图片文件无效");
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        source.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.size() <= 0 || attributes.size() > maxFileBytes) {
                    throw fileTooLarge();
                }
                if (!"jpg".equals(source.extension()) && !"png".equals(source.extension())) {
                    throw BusinessException.badRequest("导入图片格式无效");
                }
                if (attributes.size() > Long.MAX_VALUE - pendingBytes) {
                    throw new BusinessException(HttpStatus.INSUFFICIENT_STORAGE, "背景图片存储空间已达上限");
                }
                pendingBytes += attributes.size();
            }

            boolean fileLimitExceeded = sources.size() > maxFiles - inventory.files();
            boolean byteLimitExceeded = inventory.bytes() > maxTotalBytes
                    || pendingBytes > maxTotalBytes - inventory.bytes();
            if (fileLimitExceeded || byteLimitExceeded) {
                throw new BusinessException(
                        HttpStatus.INSUFFICIENT_STORAGE,
                        "背景图片存储空间不足，无法导入数据包中的背景图"
                );
            }

            for (ImportAssetSource source : sources) {
                ImportedAsset stored = copyImportedAsset(safeDirectory, source);
                imported.add(stored);
            }
            return List.copyOf(imported);
        } catch (BusinessException exception) {
            deleteImportedAssetsLocked(imported);
            throw exception;
        } catch (IOException exception) {
            deleteImportedAssetsLocked(imported);
            log.error("Failed to persist portable-package background assets", exception);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "导入背景图片保存失败");
        } finally {
            storageLock.unlock();
        }
    }

    // 只在导入回滚完成后调用；不能复用已完成的事务和连接。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteImportedAssets(Collection<ImportedAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        Set<String> referencedFiles = lockReferencedFilenames();
        storageLock.lock();
        try {
            ensureSafeStorageDirectory();
            deleteImportedAssetsLocked(assets.stream()
                    .filter(asset -> asset != null && !referencedFiles.contains(asset.filename()))
                    .toList());
        } catch (IOException exception) {
            throw new IllegalStateException("无法安全清理回滚的背景图片", exception);
        } finally {
            storageLock.unlock();
        }
    }

    private ImportedAsset copyImportedAsset(Path safeDirectory, ImportAssetSource source) throws IOException {
        Path temporary = Files.createTempFile(safeDirectory, ".upload-", ".tmp");
        try {
            Files.copy(source.path(), temporary, StandardCopyOption.REPLACE_EXISTING);
            long bytes = Files.size(temporary);
            makePubliclyReadable(temporary);

            Path target;
            String filename;
            do {
                filename = UUID.randomUUID().toString().replace("-", "") + "." + source.extension();
                target = safeDirectory.resolve(filename).normalize();
            } while (Files.exists(target, LinkOption.NOFOLLOW_LINKS));
            if (!target.startsWith(safeDirectory) || !safeDirectory.equals(target.getParent())) {
                throw new IOException("导入图片目标路径无效");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
            temporary = null;
            return new ImportedAsset(source.key(), filename, managedUrlPrefix + filename, bytes);
        } finally {
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void deleteImportedAssetsLocked(Collection<ImportedAsset> assets) {
        for (ImportedAsset asset : assets) {
            if (asset == null || asset.filename() == null
                    || !GENERATED_FILENAME.matcher(asset.filename()).matches()) {
                continue;
            }
            Path target = backgroundDirectory.resolve(asset.filename()).normalize();
            if (!backgroundDirectory.equals(target.getParent())) {
                continue;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                    Files.deleteIfExists(target);
                }
            } catch (java.nio.file.NoSuchFileException ignored) {
                // Already absent is the desired cleanup state.
            } catch (IOException exception) {
                log.error("Failed to remove rolled-back imported background {}", asset.filename(), exception);
            }
        }
    }

    private CleanupResult cleanupOrphansLocked(Path safeDirectory, Set<String> referencedFiles) throws IOException {
        Instant cutoff = clock.instant().minusMillis(orphanGraceMs);
        int scanned = 0;
        int referenced = 0;
        int graceProtected = 0;
        int deleted = 0;
        long deletedBytes = 0;

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(safeDirectory)) {
            for (Path entry : entries) {
                String filename = entry.getFileName().toString();
                BasicFileAttributes attributes = readManagedRegularFileAttributes(safeDirectory, entry);
                if (attributes == null) {
                    continue;
                }

                boolean generatedFile = GENERATED_FILENAME.matcher(filename).matches();
                boolean temporaryFile = TEMPORARY_FILENAME.matcher(filename).matches();
                if (!generatedFile && !temporaryFile) {
                    continue;
                }
                if (generatedFile) {
                    scanned++;
                    if (referencedFiles.contains(filename)) {
                        referenced++;
                        continue;
                    }
                }

                if (!attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                    if (generatedFile) {
                        graceProtected++;
                    }
                    continue;
                }

                try {
                    long bytes = attributes.size();
                    if (Files.deleteIfExists(entry)) {
                        deleted++;
                        deletedBytes += bytes;
                    }
                } catch (IOException exception) {
                    log.warn("Failed to delete orphan background file {}", filename, exception);
                }
            }
        }

        return new CleanupResult(scanned, referenced, graceProtected, deleted, deletedBytes, false);
    }

    private Set<String> lockReferencedFilenames() {
        // 全部入口统一 DB site 行锁 → storageLock；同库共享上传目录的实例也参与协调。
        List<SiteConfig> configs = siteConfigMapper.selectAllForUpdate();
        Set<String> result = new HashSet<>();
        if (configs == null || configs.isEmpty()) {
            throw new IllegalStateException("站点配置缺失，跳过背景图片清理");
        }
        for (SiteConfig config : configs) {
            if (config == null) {
                continue;
            }
            addManagedReference(result, config.getBackgroundImage());
            addManagedReference(result, config.getMobileBackgroundImage());
        }
        return result;
    }

    private void addManagedReference(Set<String> references, String value) {
        String filename = backgroundReferences.filename(value);
        if (filename != null) references.add(filename);
    }

    private StorageInventory inspectInventory(Path safeDirectory) throws IOException {
        int files = 0;
        long bytes = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(safeDirectory)) {
            for (Path entry : entries) {
                String filename = entry.getFileName().toString();
                if (!GENERATED_FILENAME.matcher(filename).matches()) {
                    continue;
                }
                BasicFileAttributes attributes = readManagedRegularFileAttributes(safeDirectory, entry);
                if (attributes == null) {
                    continue;
                }
                files++;
                long fileBytes = attributes.size();
                bytes = fileBytes > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + fileBytes;
            }
        }
        return new StorageInventory(files, bytes);
    }

    private BasicFileAttributes readManagedRegularFileAttributes(Path safeDirectory, Path entry) throws IOException {
        if (!safeDirectory.equals(entry.toAbsolutePath().normalize().getParent())) {
            return null;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isRegularFile() && !attributes.isSymbolicLink() ? attributes : null;
    }

    private void enforceQuota(StorageInventory inventory, long pendingBytes) {
        boolean fileLimitExceeded = inventory.files() >= maxFiles;
        boolean byteLimitExceeded = inventory.bytes() > maxTotalBytes
                || pendingBytes > maxTotalBytes - inventory.bytes();
        if (fileLimitExceeded || byteLimitExceeded) {
            throw new BusinessException(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "背景图片存储空间已达上限，请保存当前配置或等待孤儿文件清理后重试"
            );
        }
    }

    private BusinessException fileTooLarge() {
        String limit;
        if (maxFileBytes % (1024 * 1024) == 0) {
            limit = maxFileBytes / (1024 * 1024) + "MB";
        } else if (maxFileBytes % 1024 == 0) {
            limit = maxFileBytes / 1024 + "KB";
        } else {
            limit = maxFileBytes + " 字节";
        }
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "图片文件不能超过 " + limit);
    }

    private Path ensureSafeStorageDirectory() throws IOException {
        Files.createDirectories(uploadRoot);
        Path realRoot = uploadRoot.toRealPath();

        if (Files.exists(backgroundDirectory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(backgroundDirectory)) {
            throw new IOException("背景图片目录不能是符号链接");
        }
        Files.createDirectories(backgroundDirectory);
        Path realBackgroundDirectory = backgroundDirectory.toRealPath();
        if (!realBackgroundDirectory.startsWith(realRoot)
                || !Files.isDirectory(realBackgroundDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("背景图片目录超出上传根目录");
        }
        return realBackgroundDirectory;
    }

    private void makePubliclyReadable(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException exception) {
            if (!file.toFile().setReadable(true, false)) {
                throw new IOException("无法设置上传图片的读取权限");
            }
        }
    }

    private void validateGeneratedFilename(String filename) {
        if (filename == null || !GENERATED_FILENAME.matcher(filename).matches()) {
            throw BusinessException.badRequest("图片文件名无效");
        }
    }

    public record StoredImage(String filename, long bytes, String url) {
    }

    public record ImportAssetSource(String key, Path path, String extension) {
    }

    public record ImportedAsset(String key, String filename, String url, long bytes) {
    }

    public record CleanupResult(
            int scanned,
            int referenced,
            int graceProtected,
            int deleted,
            long deletedBytes,
            boolean skipped
    ) {
        private static CleanupResult skippedResult() {
            return new CleanupResult(0, 0, 0, 0, 0, true);
        }
    }

    private record StorageInventory(int files, long bytes) {
    }
}
