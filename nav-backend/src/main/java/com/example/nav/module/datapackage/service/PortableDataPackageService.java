package com.example.nav.module.datapackage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels;
import com.example.nav.module.datapackage.model.PortablePackageModels.AssetDescriptor;
import com.example.nav.module.datapackage.model.PortablePackageModels.ConfirmResponse;
import com.example.nav.module.datapackage.model.PortablePackageModels.CountsComparison;
import com.example.nav.module.datapackage.model.PortablePackageModels.DiffCounts;
import com.example.nav.module.datapackage.model.PortablePackageModels.DiffSummary;
import com.example.nav.module.datapackage.model.PortablePackageModels.Issue;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobResponse;
import com.example.nav.module.datapackage.model.PortablePackageModels.JobStage;
import com.example.nav.module.datapackage.model.PortablePackageModels.PackageInfo;
import com.example.nav.module.datapackage.model.PortablePackageModels.ParsedPackage;
import com.example.nav.module.datapackage.model.PortablePackageModels.PortableData;
import com.example.nav.module.datapackage.model.PortablePackageModels.PreviewResponse;
import com.example.nav.module.datapackage.model.PortablePackageModels.ResourceCounts;
import com.example.nav.module.datapackage.service.PortableDataSnapshotService.Snapshot;
import com.example.nav.module.datapackage.service.PortablePackageWriter.ExportedPackage;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PortableDataPackageService {

    static final long PREVIEW_TTL_MINUTES = 15;

    private final PortablePackageWriter packageWriter;
    private final PortablePackageReader packageReader;
    private final PortableDataSnapshotService snapshotService;
    private final PortableImportTransactionService transactionService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final Clock clock;
    private final Path previewRoot;
    private final Map<String, PreviewState> previews = new ConcurrentHashMap<>();
    private final PortableImportJobStore jobStore;
    private final PortableImportCommitStore commitStore;
    private final Object confirmationMonitor = new Object();
    private volatile JobState activeJob;

    @Autowired
    public PortableDataPackageService(
            PortablePackageWriter packageWriter,
            PortablePackageReader packageReader,
            PortableDataSnapshotService snapshotService,
            PortableImportTransactionService transactionService,
            UserMapper userMapper,
            ObjectMapper objectMapper,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
            PortableImportJobStore jobStore,
            PortableImportCommitStore commitStore
    ) {
        this(
                packageWriter,
                packageReader,
                snapshotService,
                transactionService,
                userMapper,
                objectMapper,
                taskExecutor,
                jobStore,
                commitStore,
                Clock.systemUTC(),
                Path.of(System.getProperty("java.io.tmpdir"), "yunlume-import-previews")
        );
    }

    PortableDataPackageService(
            PortablePackageWriter packageWriter,
            PortablePackageReader packageReader,
            PortableDataSnapshotService snapshotService,
            PortableImportTransactionService transactionService,
            UserMapper userMapper,
            ObjectMapper objectMapper,
            TaskExecutor taskExecutor,
            PortableImportJobStore jobStore,
            PortableImportCommitStore commitStore,
            Clock clock,
            Path previewRoot
    ) {
        this.packageWriter = packageWriter;
        this.packageReader = packageReader;
        this.snapshotService = snapshotService;
        this.transactionService = transactionService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.jobStore = jobStore;
        this.commitStore = commitStore;
        this.clock = clock;
        this.previewRoot = previewRoot.toAbsolutePath().normalize();
    }

    public ExportedPackage exportPackage() {
        return packageWriter.exportPackage();
    }

    public PreviewResponse preview(MultipartFile file, Authentication authentication) {
        long userId = currentAdminId(authentication);
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择 ZIP 数据包");
        }
        if (file.getSize() > PortablePackageModels.MAX_ARCHIVE_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "ZIP 上传文件不能超过 64MiB");
        }

        cleanupExpired();
        Path directory = createPreviewDirectory();
        Path archive = directory.resolve("package.zip");
        Path extracted = directory.resolve("extracted");
        try {
            file.transferTo(archive);
            long actualBytes = Files.size(archive);
            if (actualBytes <= 0 || actualBytes > PortablePackageModels.MAX_ARCHIVE_BYTES) {
                throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "ZIP 上传文件不能超过 64MiB");
            }
            ParsedPackage parsed = packageReader.read(archive, extracted);
            Snapshot current = snapshotService.capture();
            PreviewResponse response = buildPreview(parsed, current, null, null);
            if (!parsed.valid()) {
                deleteTree(directory);
                return response;
            }

            String token = randomId();
            Instant expiresAt = clock.instant().plus(PREVIEW_TTL_MINUTES, ChronoUnit.MINUTES);
            PreviewState state = new PreviewState(
                    token,
                    userId,
                    parsed.archiveSha256(),
                    current.revision(),
                    expiresAt,
                    directory,
                    archive,
                    extracted,
                    parsed
            );
            previews.put(token, state);
            return buildPreview(parsed, current, token, expiresAt);
        } catch (BusinessException exception) {
            deleteTree(directory);
            throw exception;
        } catch (RuntimeException exception) {
            deleteTree(directory);
            throw exception;
        } catch (IOException exception) {
            deleteTree(directory);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存导入预检文件");
        }
    }

    public ConfirmResponse confirm(String token, Authentication authentication) {
        long userId = currentAdminId(authentication);
        if (token == null || token.isBlank()) throw BusinessException.notFound("导入预检不存在或已过期");

        synchronized (confirmationMonitor) {
            PortableImportJobStore.StoredJob existingJob = commitStore.findByPreviewToken(token)
                    .map(PortableImportCommitStore.CommittedImport::asCompletedJob)
                    .or(() -> jobStore.findByPreviewToken(token))
                    .orElse(null);
            if (existingJob != null) {
                if (existingJob.userId() != userId) {
                    throw BusinessException.notFound("导入预检不存在或已过期");
                }
                return new ConfirmResponse(existingJob.jobId());
            }

            PreviewState preview = previews.get(token);
            if (preview == null || !preview.expiresAt().isAfter(clock.instant())) {
                discardPreview(token, preview);
                throw BusinessException.notFound("导入预检不存在或已过期");
            }
            if (preview.userId() != userId) {
                throw BusinessException.notFound("导入预检不存在或已过期");
            }
            if (!preview.archiveSha256().equals(sha256(preview.archive()))) {
                discardPreview(token, preview);
                throw BusinessException.conflict("预检文件已变化，请重新上传");
            }
            Snapshot current = snapshotService.capture();
            if (!preview.businessRevision().equals(current.revision())) {
                throw BusinessException.conflict("业务数据在预检后已变化，请重新预检");
            }
            String jobId = randomId();
            JobState job = new JobState(jobId, token, userId, clock.instant());
            PortableImportJobStore.ClaimResult claim = jobStore.claim(job.stored());
            if (claim.outcome() == PortableImportJobStore.ClaimOutcome.PREVIEW_ALREADY_CLAIMED) {
                PortableImportJobStore.StoredJob claimed = jobStore.findByPreviewToken(token)
                        .orElseThrow(() -> new BusinessException(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "导入任务已创建但状态暂时不可用"
                        ));
                if (claimed.userId() != userId) {
                    throw BusinessException.notFound("导入预检不存在或已过期");
                }
                return new ConfirmResponse(claimed.jobId());
            }
            if (claim.outcome() == PortableImportJobStore.ClaimOutcome.IMPORT_RUNNING) {
                throw BusinessException.conflict("已有导入任务正在执行，请稍后重试");
            }
            job.lease = claim.lease();
            if (!previews.remove(token, preview)) {
                jobStore.abandon(job.lease, job.stored());
                throw BusinessException.conflict("该预检已被确认或失效");
            }
            activeJob = job;
            try {
                taskExecutor.execute(() -> runImport(preview, job));
            } catch (RuntimeException exception) {
                activeJob = null;
                jobStore.abandon(job.lease, job.stored());
                deleteTree(preview.directory());
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "导入任务暂时无法启动");
            }
            return new ConfirmResponse(jobId);
        }
    }

    public JobResponse job(String jobId, Authentication authentication) {
        long userId = currentAdminId(authentication);
        PortableImportJobStore.StoredJob job = commitStore.findByJobId(jobId)
                .map(PortableImportCommitStore.CommittedImport::asCompletedJob)
                .or(() -> jobStore.findJob(jobId))
                .orElse(null);
        if (job == null || job.userId() != userId) {
            throw BusinessException.notFound("导入任务不存在或已过期");
        }
        return job.response();
    }

    public JobResponse currentJob(Authentication authentication) {
        long userId = currentAdminId(authentication);
        PortableImportJobStore.StoredJob selected = jobStore.findCurrent(userId).orElse(null);
        if (selected == null || selected.userId() != userId
                || selected.stage() == JobStage.COMPLETED || selected.stage() == JobStage.FAILED) {
            throw BusinessException.notFound("当前管理员没有可恢复的导入任务");
        }
        return selected.response();
    }

    private void runImport(PreviewState preview, JobState job) {
        job.startedAt = clock.instant();
        job.stage = JobStage.PREPARING;
        job.message = "正在复核数据包与业务版本";
        try {
            persist(job);
            if (!preview.expiresAt().isAfter(clock.instant())) {
                throw BusinessException.conflict("预检已过期，请重新上传");
            }
            if (!preview.archiveSha256().equals(sha256(preview.archive()))) {
                throw BusinessException.conflict("预检文件已变化，请重新上传");
            }
            Snapshot before = snapshotService.capture();
            if (!preview.businessRevision().equals(before.revision())) {
                throw BusinessException.conflict("业务数据在导入开始前已变化，请重新预检");
            }

            Path confirmedExtraction = preview.directory().resolve("confirmed-extracted-" + randomId());
            ParsedPackage confirmed = packageReader.read(preview.archive(), confirmedExtraction);
            if (!confirmed.valid() || !preview.archiveSha256().equals(confirmed.archiveSha256())) {
                throw BusinessException.conflict("数据包复核失败，请重新上传并预检");
            }

            transactionService.replaceBusinessData(
                    confirmed,
                    confirmedExtraction,
                    preview.businessRevision(),
                    job.jobId,
                    job.previewToken,
                    job.userId,
                    job.createdAt,
                    job.startedAt,
                    () -> {
                        job.stage = JobStage.WRITING;
                        job.message = "正在事务性替换业务数据";
                        persist(job);
                    },
                    () -> {
                        job.stage = JobStage.VERIFYING;
                        job.message = "正在同一事务内验证导入结果";
                        persist(job);
                    }
            );

            completeFromDatabase(job);
            try {
                persist(job);
            } catch (RuntimeException redisFailure) {
                // The database marker and business data are already committed.
                // Redis completion is best-effort and cannot reverse that truth.
                log.warn("Portable data import job {} committed but Redis completion persistence failed",
                        job.jobId, redisFailure);
            }
        } catch (RuntimeException exception) {
            try {
                PortableImportCommitStore.CommittedImport committed =
                        commitStore.findByJobId(job.jobId).orElse(null);
                if (committed != null) {
                    completeFromDatabase(job);
                    log.warn("Portable data import job {} returned an exception after its database commit",
                            job.jobId, exception);
                    return;
                }
            } catch (RuntimeException unavailable) {
                job.message = "导入结果暂时无法确认；数据库恢复后将按提交标记核对";
                log.error("Cannot determine durable terminal truth for portable import {}",
                        job.jobId, unavailable);
                return;
            }
            JobStage failedStage = job.stage;
            job.stage = JobStage.FAILED;
            job.message = "导入失败；事务未提交，数据库写入已回滚";
            job.error = safeError(exception);
            job.finishedAt = clock.instant();
            try {
                persist(job);
            } catch (BusinessException staleLease) {
                log.warn("Portable data import job {} could not persist failure state after losing its lease",
                        job.jobId);
            }
            // Keep the public job response deliberately generic, but retain the
            // original exception server-side so an operator can diagnose the
            // failure using the task id shown in the UI.
            log.warn("Portable data import job {} failed during {}", job.jobId, failedStage, exception);
        } finally {
            try {
                jobStore.release(job.lease);
            } catch (RuntimeException releaseFailure) {
                log.warn("Portable data import job {} could not release its Redis lease",
                        job.jobId, releaseFailure);
            } finally {
                if (activeJob == job) activeJob = null;
                deleteTree(preview.directory());
            }
        }
    }

    private void persist(JobState job) {
        job.heartbeatAt = clock.instant();
        jobStore.save(job.lease, job.stored());
    }


    private void completeFromDatabase(JobState job) {
        job.stage = JobStage.COMPLETED;
        job.message = "导入完成";
        job.error = null;
        job.finishedAt = clock.instant();
    }

    private PreviewResponse buildPreview(
            ParsedPackage parsed,
            Snapshot current,
            String token,
            Instant expiresAt
    ) {
        ResourceCounts currentCounts = counts(current.data(), current.assets().size());
        Map<String, AssetDescriptor> importedAssets = referencedAssets(parsed.data(), parsed.assetsByKey());
        ResourceCounts importedCounts = counts(parsed.data(), importedAssets.size());
        Map<String, AssetDescriptor> currentAssets = current.assets().stream()
                .map(PortableDataSnapshotService.SnapshotAsset::descriptor)
                .collect(Collectors.toMap(
                        AssetDescriptor::key,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        DiffSummary diff = diff(current.data(), parsed.data(), currentAssets, importedAssets);
        PackageInfo info = new PackageInfo(
                parsed.manifest() == null ? 0 : parsed.manifest().formatVersion(),
                parsed.manifest() == null || parsed.manifest().exportedAt() == null
                        ? Instant.EPOCH : parsed.manifest().exportedAt(),
                parsed.manifest() == null ? "" : normalize(parsed.manifest().generator()),
                parsed.archiveSha256()
        );
        return new PreviewResponse(
                token,
                expiresAt,
                info,
                new CountsComparison(currentCounts, importedCounts),
                diff,
                parsed.errors(),
                parsed.warnings()
        );
    }

    private ResourceCounts counts(PortableData data, int assets) {
        if (data == null) return new ResourceCounts(0, 0, 0, 0, 0, assets);
        return new ResourceCounts(
                data.siteConfig() == null ? 0 : 1,
                size(data.categories()),
                size(data.bookmarks()),
                size(data.searchEngines()),
                size(data.customLinks()),
                assets
        );
    }

    private Map<String, AssetDescriptor> referencedAssets(
            PortableData data,
            Map<String, AssetDescriptor> available
    ) {
        if (data == null || data.siteConfig() == null || available == null || available.isEmpty()) {
            return Map.of();
        }
        Map<String, AssetDescriptor> result = new LinkedHashMap<>();
        String desktop = data.siteConfig().backgroundImageAssetKey();
        String mobile = data.siteConfig().mobileBackgroundImageAssetKey();
        if (desktop != null && available.containsKey(desktop)) result.put(desktop, available.get(desktop));
        if (mobile != null && available.containsKey(mobile)) result.put(mobile, available.get(mobile));
        return Map.copyOf(result);
    }

    private DiffSummary diff(
            PortableData current,
            PortableData imported,
            Map<String, AssetDescriptor> currentAssets,
            Map<String, AssetDescriptor> importedAssets
    ) {
        DiffCounts site = diffSingle(current == null ? null : current.siteConfig(), imported == null ? null : imported.siteConfig());
        DiffCounts categories = diffList(
                current == null ? List.of() : current.categories(),
                imported == null ? List.of() : imported.categories(),
                PortablePackageModels.CategoryData::key);
        DiffCounts bookmarks = diffList(
                current == null ? List.of() : current.bookmarks(),
                imported == null ? List.of() : imported.bookmarks(),
                PortablePackageModels.BookmarkData::key);
        DiffCounts search = diffList(
                current == null ? List.of() : current.searchEngines(),
                imported == null ? List.of() : imported.searchEngines(),
                PortablePackageModels.SearchEngineData::key);
        DiffCounts links = diffList(
                current == null ? List.of() : current.customLinks(),
                imported == null ? List.of() : imported.customLinks(),
                PortablePackageModels.CustomLinkData::key);
        DiffCounts assets = diffList(
                new ArrayList<>(currentAssets.values()),
                new ArrayList<>(importedAssets.values()),
                AssetDescriptor::key);
        DiffCounts total = site.plus(categories).plus(bookmarks).plus(search).plus(links).plus(assets);
        return new DiffSummary(site, categories, bookmarks, search, links, assets, total);
    }

    private DiffCounts diffSingle(Object current, Object imported) {
        if (current == null && imported == null) return new DiffCounts(0, 0, 0, 1);
        if (current == null) return new DiffCounts(1, 0, 0, 0);
        if (imported == null) return new DiffCounts(0, 0, 1, 0);
        return current.equals(imported) ? new DiffCounts(0, 0, 0, 1) : new DiffCounts(0, 1, 0, 0);
    }

    private <T> DiffCounts diffList(List<T> current, List<T> imported, Function<T, String> key) {
        Map<String, T> oldMap = safe(current).stream().filter(java.util.Objects::nonNull)
                .filter(item -> key.apply(item) != null)
                .collect(Collectors.toMap(key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, T> newMap = safe(imported).stream().filter(java.util.Objects::nonNull)
                .filter(item -> key.apply(item) != null)
                .collect(Collectors.toMap(key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        for (Map.Entry<String, T> entry : newMap.entrySet()) {
            T old = oldMap.get(entry.getKey());
            if (old == null) added++;
            else if (old.equals(entry.getValue())) unchanged++;
            else updated++;
        }
        int deleted = (int) oldMap.keySet().stream().filter(keyValue -> !newMap.containsKey(keyValue)).count();
        return new DiffCounts(added, updated, deleted, unchanged);
    }

    private Path createPreviewDirectory() {
        try {
            Files.createDirectories(previewRoot);
            if (Files.isSymbolicLink(previewRoot)) {
                throw new IOException("预检根目录不能是符号链接");
            }
            return Files.createTempDirectory(previewRoot, "preview-");
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "无法创建导入预检目录");
        }
    }

    private long currentAdminId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw BusinessException.unauthorized("未登录或登录已失效");
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, authentication.getName())
                .eq(User::getStatus, true)
                .last("LIMIT 1"));
        if (user == null || user.getId() == null || !"admin".equalsIgnoreCase(user.getRole())) {
            throw BusinessException.unauthorized("管理员身份已失效");
        }
        return user.getId();
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public void renewActiveImportLease() {
        JobState running = activeJob;
        if (running != null && running.stage != JobStage.COMPLETED && running.stage != JobStage.FAILED) {
            if (!jobStore.heartbeat(running.lease)) {
                activeJob = null;
                log.warn("Portable data import job {} lost its Redis mutex", running.jobId);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpired() {
        Instant now = clock.instant();
        previews.forEach((token, preview) -> {
            if (!preview.expiresAt().isAfter(now) && previews.remove(token, preview)) {
                deleteTree(preview.directory());
            }
        });
    }

    private void discardPreview(String token, PreviewState preview) {
        if (preview != null && previews.remove(token, preview)) deleteTree(preview.directory());
    }

    private void deleteTree(Path root) {
        if (root == null || !root.toAbsolutePath().normalize().startsWith(previewRoot)) return;
        try {
            if (!Files.exists(root)) return;
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        log.warn("Failed to delete portable import temporary file {}", path.getFileName());
                    }
                });
            }
        } catch (IOException exception) {
            log.warn("Failed to clean portable import temporary directory");
        }
    }

    private String sha256(Path path) {
        try {
            return PortableDataSnapshotService.sha256(path);
        } catch (IOException exception) {
            throw BusinessException.conflict("预检文件已失效，请重新上传");
        }
    }

    private Issue safeError(RuntimeException exception) {
        if (exception instanceof BusinessException business && business.getMessage() != null) {
            return new Issue("IMPORT_REJECTED", null, business.getMessage());
        }
        return new Issue("IMPORT_FAILED", null, "导入任务执行失败");
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record PreviewState(
            String token,
            long userId,
            String archiveSha256,
            String businessRevision,
            Instant expiresAt,
            Path directory,
            Path archive,
            Path extracted,
            ParsedPackage parsed
    ) {
    }

    private static final class JobState {
        private final String jobId;
        private final String previewToken;
        private final long userId;
        private final Instant createdAt;
        private volatile JobStage stage = JobStage.PREPARING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String message = "任务等待执行";
        private volatile Issue error;
        private volatile Instant heartbeatAt;
        private volatile PortableImportJobStore.Lease lease;

        private JobState(String jobId, String previewToken, long userId, Instant createdAt) {
            this.jobId = jobId;
            this.previewToken = previewToken;
            this.userId = userId;
            this.createdAt = createdAt;
            this.heartbeatAt = createdAt;
        }

        private PortableImportJobStore.StoredJob stored() {
            return new PortableImportJobStore.StoredJob(
                    jobId,
                    previewToken,
                    userId,
                    stage,
                    createdAt,
                    startedAt,
                    finishedAt,
                    message,
                    error,
                    heartbeatAt
            );
        }
    }
}
