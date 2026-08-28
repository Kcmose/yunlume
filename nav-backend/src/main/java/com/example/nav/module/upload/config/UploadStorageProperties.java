package com.example.nav.module.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@ConfigurationProperties(prefix = "nav.upload")
public class UploadStorageProperties {

    public static final long ABSOLUTE_MAX_FILE_BYTES = 10 * 1024 * 1024L;

    private String directory = "./uploads";
    private String baseUrl = "/uploads";
    @Min(1)
    @Max(ABSOLUTE_MAX_FILE_BYTES)
    private long maxBytes = ABSOLUTE_MAX_FILE_BYTES;
    @Min(1)
    private long maxTotalBytes = 1024 * 1024 * 1024L;
    @Min(1)
    private int maxFiles = 500;
    @Min(0)
    private long orphanGraceMs = 24 * 60 * 60 * 1000L;

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public long getOrphanGraceMs() {
        return orphanGraceMs;
    }

    public void setOrphanGraceMs(long orphanGraceMs) {
        this.orphanGraceMs = orphanGraceMs;
    }
}
