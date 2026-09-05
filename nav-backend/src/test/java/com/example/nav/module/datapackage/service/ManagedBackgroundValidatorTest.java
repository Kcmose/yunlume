package com.example.nav.module.datapackage.service;

import com.example.nav.module.datapackage.model.PortablePackageModels.AssetDescriptor;
import com.example.nav.module.datapackage.model.PortablePackageModels.PortableData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SearchEngineData;
import com.example.nav.module.datapackage.model.PortablePackageModels.SiteConfigData;
import com.example.nav.module.upload.config.UploadStorageProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedBackgroundValidatorTest {
    private static final String FILENAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png";
    private static final String ASSET_KEY = "asset-image";

    static Stream<Arguments> managedUrls() {
        return Stream.of(
                Arguments.of("/uploads", "/uploads/backgrounds/" + FILENAME + "?v=1#desktop"),
                Arguments.of("/uploads", "/uploads/%62ackgrounds/" + FILENAME + "?v=1"),
                Arguments.of("/uploads", "/%75ploads/backgrounds/" + FILENAME + "#desktop"),
                Arguments.of("https://cdn.example.test/uploads",
                        "HTTPS://CDN.EXAMPLE.TEST/uploads/backgrounds/" + FILENAME + "?v=1#desktop")
        );
    }

    @ParameterizedTest
    @MethodSource("managedUrls")
    void everyManagedUriSpellingRequiresPackagedAssetsForBothBackgrounds(String base, String url) {
        var result = validator(base).validate(data(url, null), Map.of());

        assertEquals(2, result.errors().size());
        assertTrue(result.errors().stream().allMatch(issue -> "MANAGED_ASSET_MISSING".equals(issue.code())));
        assertEquals(Set.of("siteConfig.backgroundImage", "siteConfig.mobileBackgroundImage"),
                result.errors().stream().map(issue -> issue.path()).collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @MethodSource("managedUrls")
    void theSameUrlsRemainValidWhenTheirReferencedAssetIsPackaged(String base, String url) {
        var descriptor = new AssetDescriptor(ASSET_KEY, "assets/background.png", "a".repeat(64), 20, "image/png");
        var result = validator(base).validate(data(url, ASSET_KEY), Map.of(ASSET_KEY, descriptor));

        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example.test/uploads/backgrounds/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png?v=1",
            "/images/background.png?v=1#desktop"
    })
    void foreignAndUnmanagedImagesKeepTheirExistingAssetOptionalContract(String url) {
        var result = validator("/uploads").validate(data(url, null), Map.of());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
    }

    private PortableDataValidator validator(String base) {
        UploadStorageProperties properties = new UploadStorageProperties();
        properties.setBaseUrl(base);
        return new PortableDataValidator(properties);
    }

    private PortableData data(String url, String assetKey) {
        var site = new SiteConfigData("site-config", "Site", "", "", "image", "#050505",
                url, assetKey, url, assetKey, "#ffffff", false, false, "", false, true, "");
        var engine = new SearchEngineData("search-engine", "Search", "", "https://example.test/?q={keyword}",
                "", true, 0, true);
        return new PortableData(site, List.of(), List.of(), List.of(engine), List.of());
    }
}
