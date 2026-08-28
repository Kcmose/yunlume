package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels;
import com.example.nav.module.datapackage.model.PortablePackageModels.AssetDescriptor;
import com.example.nav.module.datapackage.model.PortablePackageModels.Issue;
import com.example.nav.module.datapackage.model.PortablePackageModels.Manifest;
import com.example.nav.module.datapackage.model.PortablePackageModels.ParsedPackage;
import com.example.nav.module.datapackage.model.PortablePackageModels.PortableData;
import com.example.nav.module.datapackage.service.PortableDataValidator.ValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

@Service
public class PortablePackageReader {

    private static final Pattern SAFE_ASSET_PATH = Pattern.compile("^assets/[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.(?:jpg|png)$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final Set<String> REQUIRED_ROOT_FILES = Set.of("manifest.json", "data.json");

    private final ObjectMapper objectMapper;
    private final PortableDataValidator validator;

    public PortablePackageReader(ObjectMapper objectMapper, PortableDataValidator validator) {
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    public ParsedPackage read(Path archive, Path extractionRoot) {
        ensureRegularArchive(archive);
        try {
            inspectCentralDirectory(archive);
            Files.createDirectories(extractionRoot);
            if (Files.isSymbolicLink(extractionRoot)) {
                throw badArchive("预检目录不能是符号链接");
            }
            Path realRoot = extractionRoot.toRealPath();
            ExtractedArchive extracted = extract(archive, realRoot);
            Manifest manifest = readJson(
                    extracted.entries().get("manifest.json"), Manifest.class, MAX_JSON_BYTES, "manifest.json");
            PortableData data = readJson(
                    extracted.entries().get("data.json"), PortableData.class, MAX_JSON_BYTES, "data.json");

            List<Issue> errors = new ArrayList<>();
            List<Issue> warnings = new ArrayList<>();
            Map<String, AssetDescriptor> assetsByKey = validateManifest(
                    manifest, extracted, errors, warnings);
            ValidationResult validation = validator.validate(data, assetsByKey);
            errors.addAll(validation.errors());
            warnings.addAll(validation.warnings());
            return new ParsedPackage(
                    manifest,
                    data,
                    Map.copyOf(assetsByKey),
                    List.copyOf(errors),
                    List.copyOf(warnings),
                    PortableDataSnapshotService.sha256(archive)
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw badArchive("ZIP 文件损坏或格式无效");
        } catch (IOException exception) {
            throw badArchive("无法读取 ZIP 数据包");
        }
    }

    /**
     * ZipInputStream intentionally ignores central-directory metadata. Inspect
     * it up front so Unix symbolic links and encrypted/ZIP64 archives cannot be
     * smuggled into the otherwise path-confined extractor.
     */
    private void inspectCentralDirectory(Path archive) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        int eocd = findEndOfCentralDirectory(bytes);
        if (eocd < 0) {
            throw badArchive("ZIP 中央目录无效");
        }
        int disk = unsignedShort(bytes, eocd + 4);
        int centralDisk = unsignedShort(bytes, eocd + 6);
        int entriesOnDisk = unsignedShort(bytes, eocd + 8);
        int entryCount = unsignedShort(bytes, eocd + 10);
        long centralSize = unsignedInt(bytes, eocd + 12);
        long centralOffset = unsignedInt(bytes, eocd + 16);
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount) {
            throw badArchive("不支持分卷 ZIP 数据包");
        }
        if (entryCount == 0xffff || centralSize == 0xffff_ffffL || centralOffset == 0xffff_ffffL) {
            throw badArchive("不支持 ZIP64 数据包");
        }
        if (entryCount > PortablePackageModels.MAX_ENTRIES) {
            throw badArchive("ZIP 文件条目不能超过 100 个");
        }
        long centralEnd = centralOffset + centralSize;
        if (centralOffset < 0 || centralEnd < centralOffset || centralEnd > eocd) {
            throw badArchive("ZIP 中央目录范围无效");
        }

        int cursor = Math.toIntExact(centralOffset);
        int expectedEnd = Math.toIntExact(centralEnd);
        Set<String> centralNames = new HashSet<>();
        for (int index = 0; index < entryCount; index++) {
            if (cursor > expectedEnd - 46 || unsignedInt(bytes, cursor) != 0x02014b50L) {
                throw badArchive("ZIP 中央目录条目无效");
            }
            int versionMadeBy = unsignedShort(bytes, cursor + 4);
            int flags = unsignedShort(bytes, cursor + 8);
            int filenameLength = unsignedShort(bytes, cursor + 28);
            int extraLength = unsignedShort(bytes, cursor + 30);
            int commentLength = unsignedShort(bytes, cursor + 32);
            long externalAttributes = unsignedInt(bytes, cursor + 38);
            long next = (long) cursor + 46L + filenameLength + extraLength + commentLength;
            if (next > expectedEnd) {
                throw badArchive("ZIP 中央目录条目长度无效");
            }
            if ((flags & 0x0001) != 0) {
                throw badArchive("不支持加密 ZIP 条目");
            }
            int hostSystem = (versionMadeBy >>> 8) & 0xff;
            if (hostSystem == 3) {
                int unixMode = (int) ((externalAttributes >>> 16) & 0xffff);
                int fileType = unixMode & 0xf000;
                if (fileType == 0xa000) {
                    throw badArchive("ZIP 不允许符号链接条目");
                }
                if (fileType != 0 && fileType != 0x8000 && fileType != 0x4000) {
                    throw badArchive("ZIP 不允许特殊文件条目");
                }
            }
            String filename = new String(bytes, cursor + 46, filenameLength, StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!centralNames.add(filename)) {
                throw badArchive("ZIP 存在重复条目: " + filename);
            }
            cursor = Math.toIntExact(next);
        }
        if (cursor != expectedEnd) {
            throw badArchive("ZIP 中央目录包含未知记录");
        }
    }

    private int findEndOfCentralDirectory(byte[] bytes) {
        if (bytes.length < 22) return -1;
        int first = Math.max(0, bytes.length - 22 - 0xffff);
        for (int index = bytes.length - 22; index >= first; index--) {
            if (unsignedInt(bytes, index) != 0x06054b50L) continue;
            int commentLength = unsignedShort(bytes, index + 20);
            if ((long) index + 22 + commentLength == bytes.length) return index;
        }
        return -1;
    }

    private int unsignedShort(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length - 2) throw badArchive("ZIP 元数据截断");
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private long unsignedInt(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length - 4) throw badArchive("ZIP 元数据截断");
        return Integer.toUnsignedLong(
                (bytes[offset] & 0xff)
                        | ((bytes[offset + 1] & 0xff) << 8)
                        | ((bytes[offset + 2] & 0xff) << 16)
                        | ((bytes[offset + 3] & 0xff) << 24)
        );
    }

    private ExtractedArchive extract(Path archive, Path realRoot) throws IOException {
        Map<String, Path> entries = new LinkedHashMap<>();
        Map<String, Long> entryBytes = new HashMap<>();
        Set<String> canonicalNames = new HashSet<>();
        long expandedBytes = 0;
        int entryCount = 0;

        try (InputStream raw = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > PortablePackageModels.MAX_ENTRIES) {
                    throw badArchive("ZIP 文件条目不能超过 100 个");
                }
                String name = validateEntry(entry);
                String canonical = name.toLowerCase(java.util.Locale.ROOT);
                if (!canonicalNames.add(canonical)) {
                    throw badArchive("ZIP 存在重复条目: " + name);
                }
                Path target = realRoot.resolve(name).normalize();
                if (!target.startsWith(realRoot)) {
                    throw badArchive("ZIP 条目路径越界: " + name);
                }

                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                    if (Files.isSymbolicLink(parent)) {
                        throw badArchive("ZIP 条目父目录不能是符号链接");
                    }
                }
                long bytes = copyBounded(zip, target, expandedBytes);
                expandedBytes += bytes;
                entries.put(name, target);
                entryBytes.put(name, bytes);
                zip.closeEntry();
            }
        }
        if (!entries.keySet().containsAll(REQUIRED_ROOT_FILES)) {
            throw badArchive("ZIP 必须包含 manifest.json 和 data.json");
        }
        return new ExtractedArchive(Map.copyOf(entries), Map.copyOf(entryBytes));
    }

    private String validateEntry(ZipEntry entry) {
        String name = entry.getName();
        if (name == null || name.isBlank() || entry.isDirectory()) {
            throw badArchive("ZIP 不允许空条目或目录条目");
        }
        if (name.indexOf('\\') >= 0 || name.startsWith("/") || name.startsWith("~")
                || name.matches("^[A-Za-z]:.*") || name.contains("\u0000")) {
            throw badArchive("ZIP 条目路径无效: " + name);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw badArchive("ZIP 条目路径无效: " + name);
            }
        }
        if (!REQUIRED_ROOT_FILES.contains(name) && !SAFE_ASSET_PATH.matcher(name).matches()) {
            throw badArchive("ZIP 包含未知顶层条目: " + name);
        }
        long declared = entry.getSize();
        if (declared > PortablePackageModels.MAX_ENTRY_BYTES) {
            throw tooLarge("ZIP 单条目展开后不能超过 16MiB");
        }
        return name;
    }

    private long copyBounded(ZipInputStream zip, Path target, long expandedBefore) throws IOException {
        long bytes = 0;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            int read;
            while ((read = zip.read(buffer)) >= 0) {
                if (read == 0) continue;
                bytes += read;
                if (bytes > PortablePackageModels.MAX_ENTRY_BYTES) {
                    throw tooLarge("ZIP 单条目展开后不能超过 16MiB");
                }
                if (expandedBefore > PortablePackageModels.MAX_EXPANDED_BYTES - bytes) {
                    throw tooLarge("ZIP 展开总量不能超过 64MiB");
                }
                output.write(buffer, 0, read);
            }
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
        return bytes;
    }

    private Map<String, AssetDescriptor> validateManifest(
            Manifest manifest,
            ExtractedArchive extracted,
            List<Issue> errors,
            List<Issue> warnings
    ) throws IOException {
        Map<String, AssetDescriptor> result = new LinkedHashMap<>();
        if (manifest == null) {
            errors.add(issue("MANIFEST_REQUIRED", "manifest.json", "清单不能为空"));
            return result;
        }
        if (manifest.formatVersion() != PortablePackageModels.FORMAT_VERSION) {
            errors.add(issue("FORMAT_VERSION", "manifest.formatVersion", "仅支持格式版本 1"));
        }
        if (manifest.exportedAt() == null) {
            errors.add(issue("EXPORTED_AT", "manifest.exportedAt", "导出时间不能为空"));
        }
        if (manifest.generator() == null || manifest.generator().isBlank() || manifest.generator().length() > 100) {
            errors.add(issue("GENERATOR", "manifest.generator", "生成器标识无效"));
        }
        validateFileDescriptor(manifest.data(), "data.json", extracted, errors);

        Set<String> paths = new HashSet<>();
        if (manifest.assets() == null) {
            errors.add(issue("ASSET_LIST_REQUIRED", "manifest.assets", "资产清单不能为空"));
        }
        List<AssetDescriptor> assets = manifest.assets() == null ? List.of() : manifest.assets();
        if (assets.size() > PortablePackageModels.MAX_ENTRIES - REQUIRED_ROOT_FILES.size()) {
            errors.add(issue("ASSET_COUNT", "manifest.assets", "资产数量超过限制"));
        }
        for (int index = 0; index < assets.size(); index++) {
            AssetDescriptor asset = assets.get(index);
            String path = "manifest.assets[" + index + "]";
            if (asset == null || asset.key() == null || asset.key().isBlank()) {
                errors.add(issue("ASSET_KEY", path, "资产标识不能为空"));
                continue;
            }
            if (result.putIfAbsent(asset.key(), asset) != null) {
                errors.add(issue("DUPLICATE_ASSET_KEY", path + ".key", "资产标识重复"));
            }
            if (asset.path() == null || !SAFE_ASSET_PATH.matcher(asset.path()).matches()) {
                errors.add(issue("ASSET_PATH", path + ".path", "资产路径格式无效"));
                continue;
            }
            if (!paths.add(asset.path())) {
                errors.add(issue("DUPLICATE_ASSET_PATH", path + ".path", "资产路径重复"));
            }
            Path file = extracted.entries().get(asset.path());
            if (file == null) {
                errors.add(issue("ASSET_MISSING", path + ".path", "清单资产在 ZIP 中不存在"));
                continue;
            }
            validateDescriptor(asset.sha256(), asset.bytes(), asset.path(), file, extracted, path, errors);
            try {
                byte[] imageBytes = Files.readAllBytes(file);
                PortableImageInspector.Inspection inspection = PortableImageInspector.inspect(
                        imageBytes, asset.mediaType());
                String expectedExtension = asset.path().endsWith(".png") ? "png" : "jpg";
                if (!inspection.extension().equals(expectedExtension)) {
                    errors.add(issue("ASSET_EXTENSION", path, "资产扩展名与图片签名不一致"));
                }
            } catch (BusinessException exception) {
                errors.add(issue("ASSET_IMAGE", path, exception.getMessage()));
            }
        }

        for (String entryName : extracted.entries().keySet()) {
            if (entryName.startsWith("assets/") && !paths.contains(entryName)) {
                warnings.add(issue("UNDECLARED_ASSET", entryName, "ZIP 中存在未在清单声明的资产，将不会导入"));
            }
        }
        return result;
    }

    private void validateFileDescriptor(
            PortablePackageModels.FileDescriptor descriptor,
            String expectedPath,
            ExtractedArchive extracted,
            List<Issue> errors
    ) throws IOException {
        if (descriptor == null || !expectedPath.equals(descriptor.path())) {
            errors.add(issue("DATA_DESCRIPTOR", "manifest.data", "data.json 清单描述无效"));
            return;
        }
        Path file = extracted.entries().get(expectedPath);
        validateDescriptor(
                descriptor.sha256(), descriptor.bytes(), expectedPath, file, extracted,
                "manifest.data", errors);
    }

    private void validateDescriptor(
            String expectedSha,
            long expectedBytes,
            String entryName,
            Path file,
            ExtractedArchive extracted,
            String path,
            List<Issue> errors
    ) throws IOException {
        if (file == null) {
            errors.add(issue("FILE_MISSING", path, "清单引用文件不存在"));
            return;
        }
        if (!SHA256.matcher(expectedSha == null ? "" : expectedSha).matches()) {
            errors.add(issue("SHA256", path + ".sha256", "SHA-256 格式无效"));
        } else if (!expectedSha.equals(PortableDataSnapshotService.sha256(file))) {
            errors.add(issue("CHECKSUM_MISMATCH", path, "文件 SHA-256 与清单不一致"));
        }
        Long actualBytes = extracted.entryBytes().get(entryName);
        if (expectedBytes < 0 || actualBytes == null || expectedBytes != actualBytes) {
            errors.add(issue("SIZE_MISMATCH", path, "文件大小与清单不一致"));
        }
    }

    private <T> T readJson(Path path, Class<T> type, int maxBytes, String name) throws IOException {
        if (path == null || Files.size(path) > maxBytes) {
            throw badArchive(name + " 超过允许大小");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return objectMapper.readValue(input, type);
        } catch (JsonProcessingException exception) {
            throw badArchive(name + " 结构或字段无效");
        }
    }

    private void ensureRegularArchive(Path archive) {
        try {
            if (archive == null || Files.isSymbolicLink(archive)
                    || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw badArchive("请选择有效的 ZIP 文件");
            }
            long bytes = Files.size(archive);
            if (bytes <= 0) throw badArchive("ZIP 文件不能为空");
            if (bytes > PortablePackageModels.MAX_ARCHIVE_BYTES) {
                throw tooLarge("ZIP 上传文件不能超过 64MiB");
            }
        } catch (IOException exception) {
            throw badArchive("无法读取 ZIP 文件");
        }
    }

    private Issue issue(String code, String path, String message) {
        return new Issue(code, path, message);
    }

    private BusinessException badArchive(String message) {
        return BusinessException.badRequest(message);
    }

    private BusinessException tooLarge(String message) {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    private record ExtractedArchive(Map<String, Path> entries, Map<String, Long> entryBytes) {
    }
}
