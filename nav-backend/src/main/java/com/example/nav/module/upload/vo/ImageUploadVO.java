package com.example.nav.module.upload.vo;

public record ImageUploadVO(
        String url,
        String filename,
        long size,
        int width,
        int height
) {
}
