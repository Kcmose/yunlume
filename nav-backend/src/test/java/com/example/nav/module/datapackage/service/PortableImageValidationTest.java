package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.example.nav.module.upload.service.BackgroundImageStorageService;
import com.example.nav.module.upload.service.ImageUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortableImageValidationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg"})
    void bothEntryPointsAcceptCompleteImages(String format) throws Exception {
        byte[] image = image(format);
        String mime = "image/" + format;
        var inspection = PortableImageInspector.inspect(image, mime);
        var result = uploads().upload(new MockMultipartFile("file", "image." + format, mime, image));
        assertEquals(8, inspection.width());
        assertEquals(6, inspection.height());
        assertEquals(8, result.width());
        assertEquals(6, result.height());
        assertEquals(image.length, Files.size(directory.resolve("backgrounds").resolve(result.filename())));
    }

    @Test
    void bothEntryPointsRejectPngWithOnlyAValidHeader() throws Exception {
        rejectWithoutWriting(Arrays.copyOf(image("png"), 33), "image/png");
    }

    @Test
    void bothEntryPointsRejectTruncatedPngPixelData() throws Exception {
        byte[] complete = image("png");
        // PNG 签名、完整 IHDR、IDAT 长度和类型之后，截断压缩像素流。
        int idatLength = ByteBuffer.wrap(complete, 33, 4).getInt();
        rejectWithoutWriting(Arrays.copyOf(complete, 41 + idatLength / 2), "image/png");
    }

    @Test
    void bothEntryPointsRejectJpegWithMissingEndMarker() throws Exception {
        byte[] complete = image("jpeg");
        rejectWithoutWriting(Arrays.copyOf(complete, complete.length - 2), "image/jpeg");
    }

    @ParameterizedTest
    @ValueSource(ints = {8001, 5000})
    void dimensionAndPixelLimitsAreCheckedBeforeDecoding(int width) throws Exception {
        byte[] header = Arrays.copyOf(image("png"), 33);
        ByteBuffer.wrap(header).putInt(16, width).putInt(20, width == 8001 ? 1 : 5000);
        CRC32 checksum = new CRC32();
        checksum.update(header, 12, 17);
        ByteBuffer.wrap(header).putInt(29, (int) checksum.getValue());
        var imported = assertThrows(BusinessException.class,
                () -> PortableImageInspector.inspect(header, "image/png"));
        var uploaded = assertThrows(BusinessException.class,
                () -> uploads().upload(new MockMultipartFile("file", "large.png", "image/png", header)));
        assertTrue(imported.getMessage().contains("尺寸"));
        assertTrue(uploaded.getMessage().contains("尺寸"));
    }

    private void rejectWithoutWriting(byte[] image, String mime) throws Exception {
        var imported = assertThrows(BusinessException.class, () -> PortableImageInspector.inspect(image, mime));
        var uploaded = assertThrows(BusinessException.class,
                () -> uploads().upload(new MockMultipartFile("file", "broken", mime, image)));
        assertEquals(400, imported.getStatus().value());
        assertEquals(400, uploaded.getStatus().value());
        try (var paths = Files.walk(directory)) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    private ImageUploadService uploads() {
        var properties = new UploadStorageProperties();
        properties.setDirectory(directory.toString());
        var mapper = mock(SiteConfigMapper.class);
        when(mapper.selectAllForUpdate()).thenReturn(List.of(new SiteConfig()));
        return new ImageUploadService(properties, new BackgroundImageStorageService(properties, mapper));
    }

    private byte[] image(String format) throws Exception {
        var image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes));
        image.flush();
        return bytes.toByteArray();
    }
}
