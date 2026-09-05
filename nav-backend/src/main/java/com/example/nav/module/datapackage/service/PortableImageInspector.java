package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.common.util.ImageContentDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

final class PortableImageInspector {

    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_DIMENSION = 8_000;

    private PortableImageInspector() {
    }

    static Inspection inspect(byte[] bytes, String declaredMediaType) {
        if (bytes == null || bytes.length == 0) {
            throw BusinessException.badRequest("背景图片内容为空");
        }
        String mediaType;
        String extension;
        if (isPng(bytes)) {
            mediaType = "image/png";
            extension = "png";
        } else if (isJpeg(bytes)) {
            mediaType = "image/jpeg";
            extension = "jpg";
        } else {
            throw BusinessException.badRequest("背景图片签名不是 JPG 或 PNG");
        }
        if (declaredMediaType == null || !mediaType.equals(declaredMediaType.toLowerCase(Locale.ROOT))) {
            throw BusinessException.badRequest("背景图片声明类型与文件签名不一致");
        }

        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (imageInput == null) throw BusinessException.badRequest("背景图片内容无效");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw BusinessException.badRequest("背景图片内容无效");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                String actualFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean formatMatches = "png".equals(extension)
                        ? actualFormat.contains("png")
                        : actualFormat.contains("jpeg") || actualFormat.contains("jpg");
                if (!formatMatches) throw BusinessException.badRequest("背景图片解码格式与签名不一致");
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw BusinessException.badRequest("背景图片尺寸超过允许范围");
                }
                ImageContentDecoder.requireCompletePixels(reader, width, height);
                return new Inspection(mediaType, extension, width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw BusinessException.badRequest("背景图片内容无效");
        }
    }

    private static boolean isPng(byte[] value) {
        return value.length >= 8
                && (value[0] & 0xff) == 0x89
                && value[1] == 0x50
                && value[2] == 0x4e
                && value[3] == 0x47
                && value[4] == 0x0d
                && value[5] == 0x0a
                && value[6] == 0x1a
                && value[7] == 0x0a;
    }

    private static boolean isJpeg(byte[] value) {
        return value.length >= 3
                && (value[0] & 0xff) == 0xff
                && (value[1] & 0xff) == 0xd8
                && (value[2] & 0xff) == 0xff;
    }

    record Inspection(String mediaType, String extension, int width, int height) {
    }
}
