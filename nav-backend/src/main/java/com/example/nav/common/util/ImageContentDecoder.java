package com.example.nav.common.util;

import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** 在调用方完成尺寸和像素数上限检查后，校验实际像素数据。 */
public final class ImageContentDecoder {

    private ImageContentDecoder() {
    }

    public static void requireCompletePixels(ImageReader reader, int width, int height) throws IOException {
        boolean[] warned = {false};
        // JPEG 解码器可能用警告报告截断，并返回填补后的图片，不能仅检查返回值。
        reader.addIIOReadWarningListener((source, warning) -> warned[0] = true);
        BufferedImage image = reader.read(0);
        try {
            if (image == null || warned[0] || image.getWidth() != width || image.getHeight() != height) {
                throw new IOException("图片像素数据不完整");
            }
        } finally {
            if (image != null) image.flush();
        }
    }
}
