package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels;
import com.example.nav.module.datapackage.model.PortablePackageModels.FileDescriptor;
import com.example.nav.module.datapackage.model.PortablePackageModels.Manifest;
import com.example.nav.module.datapackage.service.PortableDataSnapshotService.Snapshot;
import com.example.nav.module.datapackage.service.PortableDataSnapshotService.SnapshotAsset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PortablePackageWriter {

    private static final String GENERATOR = "yunlume/portable-data-v1";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final PortableDataSnapshotService snapshotService;
    private final PortableDataValidator dataValidator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PortablePackageWriter(
            PortableDataSnapshotService snapshotService,
            PortableDataValidator dataValidator,
            ObjectMapper objectMapper
    ) {
        this(snapshotService, dataValidator, objectMapper, Clock.systemUTC());
    }

    PortablePackageWriter(
            PortableDataSnapshotService snapshotService,
            PortableDataValidator dataValidator,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.snapshotService = snapshotService;
        this.dataValidator = dataValidator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ExportedPackage exportPackage() {
        Snapshot snapshot = snapshotService.capture();
        PortableDataValidator.ValidationResult validation = dataValidator.validate(
                snapshot.data(),
                snapshot.assets().stream()
                        .map(SnapshotAsset::descriptor)
                        .collect(Collectors.toMap(
                                PortablePackageModels.AssetDescriptor::key,
                                Function.identity()
                        ))
        );
        if (!validation.errors().isEmpty()) {
            var first = validation.errors().get(0);
            throw BusinessException.badRequest(
                    "当前业务数据不满足备份恢复规则，无法导出：" + first.path() + " " + first.message());
        }
        try {
            byte[] dataBytes = objectMapper.writeValueAsBytes(snapshot.data());
            ensureEntrySize("data.json", dataBytes.length);

            long expandedBytes = dataBytes.length;
            for (SnapshotAsset asset : snapshot.assets()) {
                long bytes = Files.size(asset.path());
                ensureEntrySize(asset.descriptor().path(), bytes);
                expandedBytes = checkedExpandedTotal(expandedBytes, bytes);
                byte[] image = Files.readAllBytes(asset.path());
                PortableImageInspector.Inspection inspection = PortableImageInspector.inspect(
                        image, asset.descriptor().mediaType());
                if (!inspection.extension().equals(asset.extension())) {
                    throw BusinessException.badRequest("当前背景图片扩展名与实际格式不一致，无法安全导出");
                }
            }

            Instant exportedAt = clock.instant();
            Manifest manifest = new Manifest(
                    PortablePackageModels.FORMAT_VERSION,
                    exportedAt,
                    GENERATOR,
                    snapshot.siteVersion(),
                    new FileDescriptor(
                            "data.json",
                            PortableDataSnapshotService.sha256(dataBytes),
                            dataBytes.length
                    ),
                    snapshot.assets().stream().map(SnapshotAsset::descriptor).toList()
            );
            byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
            ensureEntrySize("manifest.json", manifestBytes.length);
            expandedBytes = checkedExpandedTotal(expandedBytes, manifestBytes.length);
            if (snapshot.assets().size() + 2 > PortablePackageModels.MAX_ENTRIES) {
                throw BusinessException.badRequest("导出数据包文件数量超过限制");
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.setLevel(Deflater.BEST_SPEED);
                writeEntry(zip, "manifest.json", manifestBytes);
                writeEntry(zip, "data.json", dataBytes);
                for (SnapshotAsset asset : snapshot.assets()) {
                    writeEntry(zip, asset.descriptor().path(), Files.readAllBytes(asset.path()));
                }
            }
            byte[] archive = output.toByteArray();
            if (archive.length > PortablePackageModels.MAX_ARCHIVE_BYTES) {
                throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "导出 ZIP 超过 64MiB 限制");
            }
            return new ExportedPackage(
                    "yunlume-" + FILE_TIME.format(exportedAt) + ".zip",
                    archive
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "数据包导出失败");
        }
    }

    private void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void ensureEntrySize(String name, long bytes) {
        if (bytes < 0 || bytes > PortablePackageModels.MAX_ENTRY_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, name + " 超过单文件 16MiB 限制");
        }
    }

    private long checkedExpandedTotal(long current, long addition) {
        if (addition > Long.MAX_VALUE - current
                || current + addition > PortablePackageModels.MAX_EXPANDED_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "导出数据包展开大小超过 64MiB 限制");
        }
        return current + addition;
    }

    public record ExportedPackage(String filename, byte[] bytes) {
    }
}
