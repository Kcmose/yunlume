package com.example.nav.module.upload.service;

import com.example.nav.module.upload.config.UploadStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ManagedBackgroundReferencesTest {
    private static final String FILENAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"", "?version=1", "#mobile", "?other=../outside.png#mobile"})
    void onlyTheUriPathParticipatesInTheFilename(String suffix) {
        assertEquals(FILENAME, references("/uploads/").filename("/uploads/backgrounds/" + FILENAME + suffix));
    }

    @Test
    void absoluteStoragePrefixRequiresItsConfiguredOriginAndPath() {
        ManagedBackgroundReferences references = references("https://cdn.example.test/uploads");
        assertEquals(FILENAME, references.filename("https://cdn.example.test/uploads/backgrounds/" + FILENAME + "?v=1"));
        assertNull(references.filename("https://other.example.test/uploads/backgrounds/" + FILENAME));
        assertNull(references.filename("/uploads/backgrounds/" + FILENAME));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/uploads/backgrounds/../../outside.png",
            "/uploads/backgrounds/%2e%2e%2foutside.png",
            "/uploads/backgrounds/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png/extra",
            "https://other.example.test/uploads/backgrounds/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
            "//other.example.test/uploads/backgrounds/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"
    })
    void unsafeOrForeignPathsNeverBecomeManagedFilenames(String url) {
        assertNull(references("/uploads").filename(url));
    }

    @Test
    void readsOnlyExistingManagedRegularFiles() throws IOException {
        Path file = Files.createDirectories(root.resolve("backgrounds")).resolve(FILENAME);
        Files.writeString(file, "image");
        ManagedBackgroundReferences references = references("/uploads");
        assertEquals(file, references.requireFile(FILENAME));
        Files.delete(file);
        assertThrows(IOException.class, () -> references.requireFile(FILENAME));
        assertThrows(IOException.class, () -> references.requireFile("../outside.png"));
    }

    @Test
    void refusesFileAndBackgroundDirectorySymlinks() throws IOException {
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve(FILENAME), "outside");
        Path backgrounds = Files.createDirectories(root.resolve("backgrounds"));
        Path link = backgrounds.resolve(FILENAME);
        symlink(link, outsideFile);
        ManagedBackgroundReferences references = references("/uploads");
        assertThrows(IOException.class, () -> references.requireFile(FILENAME));
        Files.delete(link);
        Files.delete(backgrounds);
        symlink(backgrounds, outside);
        assertThrows(IOException.class, () -> references.requireFile(FILENAME));
        assertEquals("outside", Files.readString(outsideFile));
    }

    private ManagedBackgroundReferences references(String baseUrl) {
        UploadStorageProperties properties = new UploadStorageProperties();
        properties.setDirectory(root.toString());
        properties.setBaseUrl(baseUrl);
        return new ManagedBackgroundReferences(properties);
    }

    private void symlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前文件系统不支持符号链接测试");
        }
    }
}
