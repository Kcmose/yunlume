package com.example.nav.module.upload.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.example.nav.module.upload.vo.ImageUploadVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImageUploadServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storesValidatedPngWithGeneratedFilename() throws Exception {
        ImageUploadService service = service(1024 * 1024);
        MockMultipartFile file = new MockMultipartFile(
                "file", "background.png", "image/png", pngBytes(8, 6));

        ImageUploadVO result = service.upload(file);

        assertEquals(8, result.width());
        assertEquals(6, result.height());
        assertTrue(result.url().matches("/uploads/backgrounds/[a-f0-9]{32}\\.png"));
        Path storedImage = tempDirectory.resolve("backgrounds").resolve(result.filename());
        assertTrue(Files.isRegularFile(storedImage));

        if (Files.getFileAttributeView(storedImage, PosixFileAttributeView.class) != null) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(storedImage);
            assertTrue(permissions.contains(PosixFilePermission.GROUP_READ));
            assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ));
        }
    }

    @Test
    void rejectsFileWhoseContentDoesNotMatchImageMimeType() {
        ImageUploadService service = service(1024 * 1024);
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", "not an image".getBytes());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.upload(file));

        assertEquals(400, exception.getStatus().value());
    }

    @Test
    void reportsTheConfiguredFileSizeLimit() {
        ImageUploadService service = service(8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "background.png", "image/png", new byte[9]);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.upload(file));

        assertEquals(413, exception.getStatus().value());
        assertEquals("图片文件不能超过 8 字节", exception.getMessage());
    }

    private ImageUploadService service(long maxBytes) {
        UploadStorageProperties properties = new UploadStorageProperties();
        properties.setDirectory(tempDirectory.toString());
        properties.setBaseUrl("/uploads");
        properties.setMaxBytes(maxBytes);
        properties.setMaxTotalBytes(maxBytes * 10);
        properties.setMaxFiles(10);
        SiteConfigMapper mapper = mock(SiteConfigMapper.class);
        BackgroundImageStorageService storageService =
                new BackgroundImageStorageService(properties, mapper);
        return new ImageUploadService(properties, storageService);
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
