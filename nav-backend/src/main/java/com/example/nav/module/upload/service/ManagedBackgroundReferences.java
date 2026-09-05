package com.example.nav.module.upload.service;

import com.example.nav.common.validation.SafeUrlRules;
import com.example.nav.module.upload.config.UploadStorageProperties;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** 受管背景只按 URI path 映射文件；query/fragment 留在配置中，不参与文件名。 */
public final class ManagedBackgroundReferences {

    public static final Pattern FILENAME = Pattern.compile("^[a-f0-9]{32}\\.(?:jpg|png)$");
    private final Path uploadRoot;
    private final URI prefix;
    private final String urlPrefix;

    public ManagedBackgroundReferences(UploadStorageProperties properties) {
        uploadRoot = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
        String base = properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()
                ? "/uploads" : properties.getBaseUrl().trim();
        while (base.length() > 1 && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!SafeUrlRules.isSafeHttpOrInternal(base) || base.contains("..")
                || base.contains("?") || base.contains("#")) {
            throw new IllegalArgumentException("上传公开地址配置无效");
        }
        urlPrefix = ("/".equals(base) ? "" : base) + "/backgrounds/";
        prefix = URI.create(urlPrefix);
    }

    public String urlPrefix() {
        return urlPrefix;
    }

    public boolean isManagedUrl(String value) {
        return managedUri(value) != null;
    }

    public String filename(String value) {
        URI uri = managedUri(value);
        if (uri == null) return null;
        String filename = uri.getPath().substring(prefix.getPath().length());
        return FILENAME.matcher(filename).matches() ? filename : null;
    }

    /** 仅解析已有普通文件，不创建目录，也不跟随背景目录或文件符号链接。 */
    public Path requireFile(String filename) throws IOException {
        if (filename == null || !FILENAME.matcher(filename).matches()) {
            throw new IOException("受管背景图片文件名无效");
        }
        Path directory = uploadRoot.resolve("backgrounds");
        Path file = directory.resolve(filename).normalize();
        if (Files.isSymbolicLink(directory) || !directory.equals(file.getParent())
                || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("受管背景图片不存在或不是普通文件");
        }
        Path realRoot = uploadRoot.toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realRoot) || !file.toRealPath().getParent().equals(realDirectory)) {
            throw new IOException("受管背景图片超出上传目录");
        }
        return file;
    }

    private URI managedUri(String value) {
        if (!SafeUrlRules.isSafeHttpOrInternal(value)) return null;
        URI uri = URI.create(value);
        if (!Objects.equals(lower(uri.getScheme()), lower(prefix.getScheme()))
                || !Objects.equals(lower(uri.getRawAuthority()), lower(prefix.getRawAuthority()))
                || uri.getPath() == null || !uri.getPath().startsWith(prefix.getPath())) return null;
        return uri;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }
}
