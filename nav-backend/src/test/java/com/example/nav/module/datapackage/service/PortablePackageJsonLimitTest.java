package com.example.nav.module.datapackage.service;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.datapackage.model.PortablePackageModels.*;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortablePackageJsonLimitTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final PortableDataValidator validator = new PortableDataValidator(new UploadStorageProperties());

    @Test void writerReaderRoundTripAcceptsValidDataJsonLargerThanFourMiB() throws Exception {
        PortableData data = data(6000, "x");
        int jsonBytes = mapper.writeValueAsBytes(data).length;
        assertTrue(jsonBytes > 4 * 1024 * 1024);
        assertTrue(jsonBytes < 16 * 1024 * 1024);
        byte[] exported = writer(data).exportPackage().bytes();
        Path archive = Files.write(temporary.resolve("large.zip"), exported);
        ParsedPackage restored = new PortablePackageReader(mapper, validator).read(archive, temporary.resolve("extracted"));
        assertTrue(restored.valid(), () -> restored.errors().toString());
        assertEquals(data, restored.data());
    }

    @Test void writerStillRejectsValidBusinessDataWhoseUtf8JsonExceedsSixteenMiB() throws Exception {
        PortableData data = data(8000, "中");
        assertTrue(validator.validate(data, java.util.Map.of()).errors().isEmpty());
        assertTrue(mapper.writeValueAsBytes(data).length > 16 * 1024 * 1024);
        assertEquals(413, assertThrows(BusinessException.class, () -> writer(data).exportPackage()).getStatus().value());
    }

    private PortablePackageWriter writer(PortableData data) {
        var snapshots = mock(PortableDataSnapshotService.class);
        when(snapshots.capture()).thenReturn(new PortableDataSnapshotService.Snapshot(data, 1, List.of(), "revision"));
        return new PortablePackageWriter(snapshots, validator, mapper);
    }

    private PortableData data(int count, String letter) {
        var bookmarks = new ArrayList<BookmarkData>();
        for (int i = 0; i < count; i++) {
            bookmarks.add(new BookmarkData("bookmark-" + i, "category", letter.repeat(100),
                    "https://example.com/" + "a".repeat(470), letter.repeat(255), letter.repeat(255),
                    i, false, true, true));
        }
        return new PortableData(new SiteConfigData("site", "Site", "", "", "color", "#ffffff",
                "", null, "", null, "#000000", false, false, "", false, false, ""),
                List.of(new CategoryData("category", "Category", "", 0, true)), bookmarks,
                List.of(new SearchEngineData("search", "Search", "", "https://example.com/?q={keyword}", "", true, 0, true)), List.of());
    }
}
