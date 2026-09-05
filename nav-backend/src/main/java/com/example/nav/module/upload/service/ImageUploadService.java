package com.example.nav.module.upload.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.common.util.ImageContentDecoder;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.example.nav.module.upload.vo.ImageUploadVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImageUploadService {

    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_DIMENSION = 8_000;

    private final long maxBytes;
    private final BackgroundImageStorageService storageService;

    public ImageUploadService(
            UploadStorageProperties properties,
            BackgroundImageStorageService storageService
    ) {
        this.maxBytes = properties.getMaxBytes();
        this.storageService = storageService;
    }

    public ImageUploadVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择要上传的图片");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "图片文件不能超过 " + readableBytes(maxBytes)
            );
        }

        ImageFormat detectedFormat = detectFormat(file);
        ImageMetadata metadata = inspectImage(file, detectedFormat);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + detectedFormat.extension;
        BackgroundImageStorageService.StoredImage storedImage = storageService.store(file, filename);
        return new ImageUploadVO(
                storedImage.url(),
                storedImage.filename(),
                storedImage.bytes(),
                metadata.width,
                metadata.height
        );
    }

    private ImageFormat detectFormat(MultipartFile file) {
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase(Locale.ROOT);
        byte[] signature;
        try (InputStream input = file.getInputStream()) {
            signature = input.readNBytes(12);
        } catch (IOException exception) {
            throw BusinessException.badRequest("无法读取图片文件");
        }

        if (isPng(signature)) {
            if (!contentType.isBlank() && !"image/png".equals(contentType)) {
                throw BusinessException.badRequest("图片 MIME 类型与文件内容不一致");
            }
            return ImageFormat.PNG;
        }
        if (isJpeg(signature)) {
            if (!contentType.isBlank() && !"image/jpeg".equals(contentType)) {
                throw BusinessException.badRequest("图片 MIME 类型与文件内容不一致");
            }
            return ImageFormat.JPEG;
        }
        throw BusinessException.badRequest("仅支持 JPG、JPEG 和 PNG 图片");
    }

    private ImageMetadata inspectImage(MultipartFile file, ImageFormat detectedFormat) {
        try (InputStream input = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw BusinessException.badRequest("图片文件内容无效");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw BusinessException.badRequest("图片文件内容无效");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                String readerFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!detectedFormat.matchesReader(readerFormat)) {
                    throw BusinessException.badRequest("图片格式与文件内容不一致");
                }
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw BusinessException.badRequest("图片尺寸过大，最长边不得超过 8000px 且总像素不得超过 2000 万");
                }
                ImageContentDecoder.requireCompletePixels(reader, width, height);
                return new ImageMetadata(width, height);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw BusinessException.badRequest("图片文件内容无效");
        }
    }

    private boolean isPng(byte[] value) {
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

    private boolean isJpeg(byte[] value) {
        return value.length >= 3
                && (value[0] & 0xff) == 0xff
                && (value[1] & 0xff) == 0xd8
                && (value[2] & 0xff) == 0xff;
    }

    private String readableBytes(long bytes) {
        if (bytes % (1024 * 1024) == 0) {
            return bytes / (1024 * 1024) + "MB";
        }
        if (bytes % 1024 == 0) {
            return bytes / 1024 + "KB";
        }
        return bytes + " 字节";
    }

    private enum ImageFormat {
        PNG("png"),
        JPEG("jpg");

        private final String extension;

        ImageFormat(String extension) {
            this.extension = extension;
        }

        private boolean matchesReader(String readerFormat) {
            return this == PNG ? readerFormat.contains("png")
                    : readerFormat.contains("jpeg") || readerFormat.contains("jpg");
        }
    }

    private record ImageMetadata(int width, int height) {
    }
}
