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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
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
    private final PortablePreviewStore previewStore;
    // 同一节点只允许两个正在展开/解析的数据包；等待确认的记录不保留解析对象。
    private final Semaphore processingSlots = new Semaphore(2);
    private final Map<String, PortablePreviewStore.Entry> processingPreviews = new ConcurrentHashMap<>();
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
            PortableImportCommitStore commitStore,
            PortablePreviewStore previewStore
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
                Path.of(System.getProperty("java.io.tmpdir"), "yunlume-import-previews"),
                previewStore
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
        this(packageWriter, packageReader, snapshotService, transactionService, userMapper, objectMapper,
                taskExecutor, jobStore, commitStore, clock, previewRoot,
                new FilePortablePreviewStore(objectMapper, clock, previewRoot.resolve("stored")));
    }

    PortableDataPackageService(
            PortablePackageWriter packageWriter, PortablePackageReader packageReader,
            PortableDataSnapshotService snapshotService, PortableImportTransactionService transactionService,
            UserMapper userMapper, ObjectMapper objectMapper, TaskExecutor taskExecutor,
            PortableImportJobStore jobStore, PortableImportCommitStore commitStore,
            Clock clock, Path previewRoot, PortablePreviewStore previewStore
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
        this.previewRoot = previewRoot.toAbsolutePath().normalize().resolve("work");
        this.previewStore = previewStore;
        PortablePreviewWorkspace.reap(this.previewRoot, clock);
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

        if (!processingSlots.tryAcquire()) throw PortablePreviewStore.full();
        PortablePreviewStore.Entry reservation = null;
        Path workingDirectory = null;
        boolean creationClean = false;
        boolean published = false;
        try {
            String token = randomId();
            Instant expiresAt = clock.instant().plus(PREVIEW_TTL_MINUTES, ChronoUnit.MINUTES);
            reservation = previewStore.reserve(token, userId, file.getSize(), expiresAt);
            processingPreviews.put(token, reservation);
            try (PortablePreviewWorkspace workspace = PortablePreviewWorkspace.create(previewRoot, clock)) {
                workingDirectory = workspace.directory();
                copyUpload(file, workspace.archive());
                ParsedPackage parsed = packageReader.read(workspace.archive(), workspace.directory().resolve("extracted"));
                Snapshot current = snapshotService.capture();
                if (!parsed.valid()) return buildPreview(parsed, current, null, null);
                // 可跨节点确认前先回收预检工作副本，预算只需覆盖一个解压目录。
                PortablePreviewStore.Entry ready = previewStore.publish(reservation, parsed.archiveSha256(),
                        current.revision(), workspace.archive(), workspace::close);
                published = true;
                return buildPreview(parsed, current, token, ready.expiresAt());
            }
        } catch (PortablePreviewWorkspace.CreationException exception) {
            creationClean = !exception.hasResidue();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "无法创建导入预检工作目录");
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存导入预检文件");
        } finally {
            if (reservation != null) processingPreviews.remove(reservation.token(), reservation);
            try {
                // 清理未完成时保留活动预留；不能把仍占用的工作目录字节视为已释放。
                if (!published && reservation != null && (creationClean || workingDirectory != null
                        && !Files.exists(workingDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                    previewStore.release(reservation);
                }
            } finally {
                processingSlots.release();
            }
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

            PortablePreviewStore.Entry preview = previewStore.find(token, userId)
                    .orElseThrow(PortablePreviewStore::missing);
            String jobId = randomId();
            JobState job = new JobState(jobId, token, userId, clock.instant());
            if (!processingSlots.tryAcquire()) throw PortablePreviewStore.full();
            boolean submitted = false;
            try {
                Snapshot current = snapshotService.capture();
                if (!preview.businessRevision().equals(current.revision())) {
                    throw BusinessException.conflict("业务数据在预检后已变化，请重新预检");
                }
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
                try {
                    job.preview = previewStore.activate(preview, jobId);
                    activeJob = job;
                    taskExecutor.execute(() -> runImport(job.preview, job));
                    submitted = true;
                } catch (RuntimeException exception) {
                    activeJob = null;
                    // 保留已确认的任务身份，网络未知后的只读查询可得到失败终态。
                    job.stage = JobStage.FAILED;
                    job.finishedAt = clock.instant();
                    job.message = "导入任务未能启动，业务数据未修改";
                    job.error = safeError(exception);
                    try {
                        persist(job);
                    } finally {
                        try { jobStore.release(job.lease); }
                        finally { previewStore.release(job.preview == null ? preview : job.preview); }
                    }
                    throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "导入任务暂时无法启动");
                }
                return new ConfirmResponse(jobId);
            } finally {
                if (!submitted) processingSlots.release();
            }
        }
    }

    public JobResponse queryByPreviewToken(String token, Authentication authentication) {
        long userId = currentAdminId(authentication);
        if (token == null || token.isBlank()) throw PortablePreviewStore.missing();
        PortableImportJobStore.StoredJob job = commitStore.findByPreviewToken(token)
                .map(PortableImportCommitStore.CommittedImport::asCompletedJob)
                .or(() -> jobStore.findByPreviewToken(token)).orElse(null);
        if (job == null || job.userId() != userId) throw PortablePreviewStore.missing();
        return job.response();
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

    private void runImport(PortablePreviewStore.Entry preview, JobState job) {
        job.startedAt = clock.instant();
        job.stage = JobStage.PREPARING;
        job.message = "正在复核数据包与业务版本";
        PortablePreviewWorkspace workspace = null;
        try {
            persist(job);
            // 确认已原子转为活动预留，后续以活动租约保护，不再按预检15分钟截止时间中止。
            try {
                workspace = PortablePreviewWorkspace.create(previewRoot, clock);
            } catch (IOException exception) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "无法创建导入工作目录");
            }
            previewStore.copyArchive(preview, workspace.archive());
            if (!preview.archiveSha256().equals(sha256(workspace.archive()))) {
                throw BusinessException.conflict("预检文件已变化，请重新上传");
            }
            Snapshot before = snapshotService.capture();
            if (!preview.businessRevision().equals(before.revision())) {
                throw BusinessException.conflict("业务数据在导入开始前已变化，请重新预检");
            }

            Path confirmedExtraction = workspace.directory().resolve("extracted");
            ParsedPackage confirmed = packageReader.read(workspace.archive(), confirmedExtraction);
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
            } catch (RuntimeException staleLease) {
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
                try {
                    if (workspace != null) workspace.close();
                    previewStore.release(preview);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("Portable import {} could not release its preview reservation", job.jobId, cleanupFailure);
                } finally {
                    processingSlots.release();
                }
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

    private void copyUpload(MultipartFile file, Path archive) throws IOException {
        try (InputStream input = file.getInputStream();
             var output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > file.getSize() || total > PortablePackageModels.MAX_ARCHIVE_BYTES) {
                    throw BusinessException.badRequest("上传文件大小与预检预留不符");
                }
                output.write(buffer, 0, read);
            }
            if (total != file.getSize()) throw BusinessException.badRequest("上传文件大小与预检预留不符");
        }
        PortablePreviewWorkspace.privateFile(archive);
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
        processingPreviews.values().forEach(entry -> {
            try { previewStore.renewProcessing(entry); }
            catch (RuntimeException failure) {
                log.warn("Cannot renew processing preview reservation {}", entry.token(), failure);
            }
        });
        JobState running = activeJob;
        if (running != null && running.stage != JobStage.COMPLETED && running.stage != JobStage.FAILED) {
            if (running.preview != null) previewStore.renew(running.preview);
            if (!jobStore.heartbeat(running.lease)) {
                activeJob = null;
                log.warn("Portable data import job {} lost its Redis mutex", running.jobId);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpired() {
        try {
            previewStore.cleanupExpired();
        } finally {
            PortablePreviewWorkspace.reap(previewRoot, clock);
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
        private volatile PortablePreviewStore.Entry preview;

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
