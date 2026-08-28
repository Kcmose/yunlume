package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.datapackage.model.PortablePackageModels;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortableDataPackageIntegrationTest {

    private static final Path UPLOAD_ROOT = createUploadRoot();

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        String postgresUrl = System.getProperty("nav.test.postgresql.url");
        if (postgresUrl == null || postgresUrl.isBlank()) {
            registry.add("spring.datasource.url", () ->
                    "jdbc:h2:mem:portable_package_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                            + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=0");
        } else {
            registry.add("spring.datasource.url", () -> postgresUrl);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () ->
                    System.getProperty("nav.test.postgresql.username", "nav_test"));
            registry.add("spring.datasource.password", () ->
                    System.getProperty("nav.test.postgresql.password", "nav_test_password"));
            registry.add("spring.sql.init.mode", () -> "never");
            registry.add("spring.h2.console.enabled", () -> "false");
        }
        registry.add("nav.upload.directory", () -> UPLOAD_ROOT.toString());
        registry.add("nav.upload.orphan-grace-ms", () -> "86400000");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private SiteConfigMapper siteConfigMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @SpyBean
    private BookmarkMapper bookmarkMapper;

    @AfterAll
    void removeUploadRoot() throws IOException {
        if (!Files.exists(UPLOAD_ROOT)) return;
        try (var paths = Files.walk(UPLOAD_ROOT)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Test temporary storage is best-effort cleanup only.
                }
            });
        }
    }

    @BeforeEach
    void verifyRequestedDatabaseProduct() throws Exception {
        String postgresUrl = System.getProperty("nav.test.postgresql.url");
        if (postgresUrl == null || postgresUrl.isBlank()) return;
        try (var connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
        }
    }

    @Test
    void endpointsRequireAdminAndExportExcludesUsersAndIncludesManagedAsset() throws Exception {
        mockMvc.perform(get("/api/admin/data/export"))
                .andExpect(status().isUnauthorized());

        User viewer = insertUser("portable-viewer", "user");
        mockMvc.perform(get("/api/admin/data/export")
                        .header("Authorization", bearer(jwtTokenService.createToken(viewer))))
                .andExpect(status().isForbidden());

        byte[] png = onePixelPng();
        String filename = "0123456789abcdef0123456789abcdef.png";
        Path background = UPLOAD_ROOT.resolve("backgrounds");
        Files.createDirectories(background);
        Files.write(background.resolve(filename), png);
        SiteConfig site = singleSite();
        site.setBackgroundType("image");
        site.setBackgroundImage("/uploads/backgrounds/" + filename);
        siteConfigMapper.updateById(site);

        byte[] archive = export(adminToken());
        Map<String, byte[]> entries = unzip(archive);
        assertEquals(Set.of("manifest.json", "data.json", "assets/asset-"
                        + sha256(png) + ".png"), new TreeSet<>(entries.keySet()));
        assertArrayEquals(png, entries.get("assets/asset-" + sha256(png) + ".png"));

        String allJson = new String(entries.get("manifest.json"), StandardCharsets.UTF_8)
                + new String(entries.get("data.json"), StandardCharsets.UTF_8);
        assertFalse(allJson.contains("portable-viewer"));
        assertFalse(allJson.contains(singleAdmin().getPassword()));
        assertFalse(allJson.contains("tokenVersion"));
        assertFalse(allJson.contains("sys_user"));
    }

    @Test
    void previewIsReadOnlyAndIdenticalPackageHasOnlyUnchangedDiffs() throws Exception {
        byte[] archive = export(adminToken());
        long categories = categoryMapper.selectCount(null);
        SiteConfig siteBefore = singleSite();
        User adminBefore = singleAdmin();

        JsonNode preview = preview(archive, adminToken(), status().isOk());

        assertTrue(preview.path("errors").isEmpty());
        assertTrue(preview.path("previewToken").isTextual());
        assertEquals(0, preview.path("diff").path("total").path("added").asInt());
        assertEquals(0, preview.path("diff").path("total").path("updated").asInt());
        assertEquals(0, preview.path("diff").path("total").path("deleted").asInt());
        assertTrue(preview.path("diff").path("total").path("unchanged").asInt() > 0);
        assertEquals(categories, categoryMapper.selectCount(null));
        assertEquals(siteBefore.getVersion(), singleSite().getVersion());
        assertEquals(adminBefore.getPassword(), singleAdmin().getPassword());
        assertEquals(adminBefore.getTokenVersion(), singleAdmin().getTokenVersion());
    }

    @Test
    void traversalUnknownDuplicateSymlinkAndOversizedEntriesAreRejected() throws Exception {
        assertPreviewBad(zip(Map.of("../manifest.json", "{}".getBytes(StandardCharsets.UTF_8))), 400);
        assertPreviewBad(zip(Map.of("manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "data.json", "{}".getBytes(StandardCharsets.UTF_8),
                "secret.txt", new byte[]{1})), 400);

        LinkedHashMap<String, byte[]> caseDuplicate = new LinkedHashMap<>();
        caseDuplicate.put("manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
        caseDuplicate.put("MANIFEST.JSON", "{}".getBytes(StandardCharsets.UTF_8));
        caseDuplicate.put("data.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertPreviewBad(zip(caseDuplicate), 400);

        byte[] symlink = markFirstEntryAsUnixSymlink(zip(Map.of(
                "manifest.json", "target".getBytes(StandardCharsets.UTF_8),
                "data.json", "{}".getBytes(StandardCharsets.UTF_8))));
        assertPreviewBad(symlink, 400);

        LinkedHashMap<String, byte[]> oversized = new LinkedHashMap<>();
        oversized.put("manifest.json", new byte[(int) PortablePackageModels.MAX_ENTRY_BYTES + 1]);
        oversized.put("data.json", "{}".getBytes(StandardCharsets.UTF_8));
        assertPreviewBad(zip(oversized), 413);
    }

    @Test
    void invalidReferencesAndMultipleDefaultsReturnSemanticErrorsWithoutTokenOrWrites() throws Exception {
        byte[] archive = export(adminToken());
        Map<String, byte[]> entries = unzip(archive);
        JsonNode data = objectMapper.readTree(entries.get("data.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("bookmarks").get(0))
                .put("categoryKey", "category-missing");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("searchEngines").get(1))
                .put("defaultEngine", true);
        byte[] invalid = replaceData(entries, data);
        long categories = categoryMapper.selectCount(null);

        JsonNode result = preview(invalid, adminToken(), status().isOk());

        assertTrue(result.path("previewToken").isMissingNode() || result.path("previewToken").isNull());
        Set<String> codes = new java.util.HashSet<>();
        result.path("errors").forEach(issue -> codes.add(issue.path("code").asText()));
        assertTrue(codes.contains("CATEGORY_REFERENCE"));
        assertTrue(codes.contains("DEFAULT_ENGINE_COUNT"));
        assertEquals(categories, categoryMapper.selectCount(null));
    }

    @Test
    void checksumMismatchAndFakeImageAreRejectedWithoutPreviewToken() throws Exception {
        byte[] baseline = export(adminToken());
        Map<String, byte[]> checksumEntries = unzip(baseline);
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(checksumEntries.get("manifest.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("data"))
                .put("sha256", "0".repeat(64));
        checksumEntries.put("manifest.json", objectMapper.writeValueAsBytes(manifest));
        JsonNode checksum = preview(zip(checksumEntries), adminToken(), status().isOk());
        assertTrue(hasIssue(checksum, "CHECKSUM_MISMATCH"));
        assertTrue(checksum.path("previewToken").isMissingNode() || checksum.path("previewToken").isNull());

        Map<String, byte[]> fakeEntries = unzip(baseline);
        JsonNode data = objectMapper.readTree(fakeEntries.get("data.json"));
        com.fasterxml.jackson.databind.node.ObjectNode site =
                (com.fasterxml.jackson.databind.node.ObjectNode) data.path("siteConfig");
        String assetKey = "asset-fake";
        String assetPath = "assets/asset-fake.png";
        byte[] fake = "not-an-image".getBytes(StandardCharsets.UTF_8);
        site.put("backgroundType", "image");
        site.put("backgroundImage", "/uploads/backgrounds/fake.png");
        site.put("backgroundImageAssetKey", assetKey);
        byte[] dataBytes = objectMapper.writeValueAsBytes(data);
        com.fasterxml.jackson.databind.node.ObjectNode fakeManifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fakeEntries.get("manifest.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) fakeManifest.path("data"))
                .put("sha256", sha256(dataBytes)).put("bytes", dataBytes.length);
        com.fasterxml.jackson.databind.node.ArrayNode assets = objectMapper.createArrayNode();
        assets.addObject().put("key", assetKey).put("path", assetPath)
                .put("sha256", sha256(fake)).put("bytes", fake.length).put("mediaType", "image/png");
        fakeManifest.set("assets", assets);
        fakeEntries.put("data.json", dataBytes);
        fakeEntries.put("manifest.json", objectMapper.writeValueAsBytes(fakeManifest));
        fakeEntries.put(assetPath, fake);
        JsonNode fakePreview = preview(zip(fakeEntries), adminToken(), status().isOk());
        assertTrue(hasIssue(fakePreview, "ASSET_IMAGE"));
        assertTrue(fakePreview.path("previewToken").isMissingNode() || fakePreview.path("previewToken").isNull());
    }

    @Test
    void previewTokenIsBoundToAdministratorAndCurrentRevision() throws Exception {
        String adminToken = adminToken();
        JsonNode first = preview(export(adminToken), adminToken, status().isOk());
        String previewToken = first.path("previewToken").asText();
        User secondAdmin = insertUser("second-portable-admin", "admin");

        mockMvc.perform(post("/api/admin/data/import/{token}/confirm", previewToken)
                        .header("Authorization", bearer(jwtTokenService.createToken(secondAdmin))))
                .andExpect(status().isNotFound());

        Category category = new Category();
        category.setName("并发新增分类");
        category.setSortOrder(999);
        category.setVisible(true);
        category.setCreatedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());
        categoryMapper.insert(category);

        mockMvc.perform(post("/api/admin/data/import/{token}/confirm", previewToken)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void roundTripReplacesBusinessDataButPreservesUserSecurityFieldsAndIncrementsSiteVersion() throws Exception {
        String token = adminToken();
        byte[] baseline = export(token);
        User beforeUser = singleAdmin();

        SiteConfig site = singleSite();
        int mutatedVersion = site.getVersion() + 1;
        site.setSiteName("等待备份恢复的名称");
        site.setVersion(mutatedVersion);
        siteConfigMapper.updateById(site);
        Long removedCategory = categoryMapper.selectList(null).get(0).getId();
        bookmarkMapper.delete(Wrappers.<Bookmark>lambdaQuery().eq(Bookmark::getCategoryId, removedCategory));
        categoryMapper.deleteById(removedCategory);

        JsonNode preview = preview(baseline, token, status().isOk());
        String previewToken = preview.path("previewToken").asText();
        String jobId = confirm(previewToken, token);
        mockMvc.perform(post("/api/admin/data/import/{token}/confirm", previewToken)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        JsonNode job = awaitTerminalJob(jobId, token);

        assertEquals("COMPLETED", job.path("stage").asText());
        assertNotEquals("等待备份恢复的名称", singleSite().getSiteName());
        assertEquals(mutatedVersion + 1, singleSite().getVersion());
        User afterUser = singleAdmin();
        assertEquals(beforeUser.getId(), afterUser.getId());
        assertEquals(beforeUser.getPassword(), afterUser.getPassword());
        assertEquals(beforeUser.getTokenVersion(), afterUser.getTokenVersion());

        mockMvc.perform(get("/api/admin/data/import/jobs/{jobId}", jobId)
                        .header("Authorization", bearer(jwtTokenService.createToken(insertUser("job-other-admin", "admin")))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/data/import/jobs/not-retained", jobId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("服务重启")));
    }

    @Test
    void roundTripNormalizesStoredEmptyNullableFields() throws Exception {
        SiteConfig site = singleSite();
        siteConfigMapper.update(null, Wrappers.<SiteConfig>lambdaUpdate()
                .eq(SiteConfig::getId, site.getId())
                .set(SiteConfig::getMobileBackgroundImage, "")
                .set(SiteConfig::getMusicUrl, ""));
        assertEquals("", singleSite().getMobileBackgroundImage());
        assertEquals("", singleSite().getMusicUrl());

        String token = adminToken();
        byte[] archive = export(token);
        String previewToken = preview(archive, token, status().isOk()).path("previewToken").asText();
        JsonNode job = awaitTerminalJob(confirm(previewToken, token), token);

        assertEquals("COMPLETED", job.path("stage").asText());
        assertNull(singleSite().getMobileBackgroundImage());
        assertNull(singleSite().getMusicUrl());
    }

    @Test
    void importedEmptyNullableFieldsClearExistingValues() throws Exception {
        SiteConfig initial = singleSite();
        siteConfigMapper.update(null, Wrappers.<SiteConfig>lambdaUpdate()
                .eq(SiteConfig::getId, initial.getId())
                .set(SiteConfig::getSiteDescription, "")
                .set(SiteConfig::getPublishUrl, "")
                .set(SiteConfig::getBackgroundImage, "")
                .set(SiteConfig::getMobileBackgroundImage, "")
                .set(SiteConfig::getMusicUrl, "")
                .set(SiteConfig::getMessageText, ""));
        String token = adminToken();
        byte[] archive = export(token);
        SiteConfig site = singleSite();
        siteConfigMapper.update(null, Wrappers.<SiteConfig>lambdaUpdate()
                .eq(SiteConfig::getId, site.getId())
                .set(SiteConfig::getSiteDescription, "temporary description")
                .set(SiteConfig::getPublishUrl, "https://example.com/publish")
                .set(SiteConfig::getBackgroundImage, "https://example.com/desktop.jpg")
                .set(SiteConfig::getMobileBackgroundImage, "https://example.com/mobile.jpg")
                .set(SiteConfig::getMusicUrl, "https://example.com/music.mp3")
                .set(SiteConfig::getMessageText, "temporary message")
                .set(SiteConfig::getVersion, site.getVersion() + 1));

        String previewToken = preview(archive, token, status().isOk()).path("previewToken").asText();
        JsonNode job = awaitTerminalJob(confirm(previewToken, token), token);

        assertEquals("COMPLETED", job.path("stage").asText());
        SiteConfig restored = singleSite();
        assertNull(restored.getSiteDescription());
        assertNull(restored.getPublishUrl());
        assertNull(restored.getBackgroundImage());
        assertNull(restored.getMobileBackgroundImage());
        assertNull(restored.getMusicUrl());
        assertNull(restored.getMessageText());
    }

    @Test
    void transactionFailureRollsBackDatabaseAndRemovesNewImportedAssets() throws Exception {
        String token = adminToken();
        String filename = "fedcba9876543210fedcba9876543210.png";
        Path background = UPLOAD_ROOT.resolve("backgrounds");
        Files.createDirectories(background);
        Files.write(background.resolve(filename), onePixelPng());
        SiteConfig site = singleSite();
        site.setBackgroundType("image");
        site.setBackgroundImage("/uploads/backgrounds/" + filename);
        siteConfigMapper.updateById(site);
        byte[] archive = export(token);
        int versionBefore = singleSite().getVersion();
        long categoriesBefore = categoryMapper.selectCount(null);
        long bookmarksBefore = bookmarkMapper.selectCount(null);
        Set<String> filesBefore = backgroundFiles(background);
        User userBefore = singleAdmin();

        org.mockito.Mockito.doThrow(new IllegalStateException("injected transaction failure"))
                .when(bookmarkMapper).insert(org.mockito.ArgumentMatchers.any(Bookmark.class));
        String jobId = confirm(preview(archive, token, status().isOk()).path("previewToken").asText(), token);
        JsonNode job = awaitTerminalJob(jobId, token);

        assertEquals("FAILED", job.path("stage").asText());
        assertEquals("IMPORT_FAILED", job.path("error").path("code").asText());
        assertEquals(versionBefore, singleSite().getVersion());
        assertEquals(categoriesBefore, categoryMapper.selectCount(null));
        assertEquals(bookmarksBefore, bookmarkMapper.selectCount(null));
        assertEquals(filesBefore, backgroundFiles(background));
        assertEquals(userBefore.getPassword(), singleAdmin().getPassword());
        assertEquals(userBefore.getTokenVersion(), singleAdmin().getTokenVersion());
    }

    private byte[] export(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/data/export").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private JsonNode preview(
            byte[] archive,
            String token,
            org.springframework.test.web.servlet.ResultMatcher expectedStatus
    ) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "portable.zip", "application/zip", archive);
        String response = mockMvc.perform(multipart("/api/admin/data/import/preview")
                        .file(file).header("Authorization", bearer(token)))
                .andExpect(expectedStatus)
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        return root.path("data");
    }

    private void assertPreviewBad(byte[] archive, int statusCode) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bad.zip", "application/zip", archive);
        mockMvc.perform(multipart("/api/admin/data/import/preview")
                        .file(file).header("Authorization", bearer(adminToken())))
                .andExpect(status().is(statusCode));
    }

    private String confirm(String previewToken, String token) throws Exception {
        String response = mockMvc.perform(post("/api/admin/data/import/{token}/confirm", previewToken)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("jobId").asText();
    }

    private JsonNode awaitTerminalJob(String jobId, String token) throws Exception {
        JsonNode job = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            String response = mockMvc.perform(get("/api/admin/data/import/jobs/{jobId}", jobId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            job = objectMapper.readTree(response).path("data");
            if (Set.of("COMPLETED", "FAILED").contains(job.path("stage").asText())) return job;
            Thread.sleep(25);
        }
        assertNotNull(job);
        throw new AssertionError("导入任务未在测试超时时间内结束: " + job);
    }

    private byte[] replaceData(Map<String, byte[]> entries, JsonNode data) throws IOException {
        LinkedHashMap<String, byte[]> changed = new LinkedHashMap<>(entries);
        byte[] dataBytes = objectMapper.writeValueAsBytes(data);
        changed.put("data.json", dataBytes);
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(entries.get("manifest.json"));
        com.fasterxml.jackson.databind.node.ObjectNode descriptor =
                (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("data");
        descriptor.put("sha256", sha256(dataBytes));
        descriptor.put("bytes", dataBytes.length);
        changed.put("manifest.json", objectMapper.writeValueAsBytes(manifest));
        return zip(changed);
    }

    private Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private byte[] markFirstEntryAsUnixSymlink(byte[] archive) {
        for (int index = 0; index <= archive.length - 46; index++) {
            if (littleInt(archive, index) != 0x02014b50) continue;
            archive[index + 5] = 3; // Unix host in version-made-by.
            int symlinkMode = 0120777;
            archive[index + 40] = (byte) (symlinkMode & 0xff);
            archive[index + 41] = (byte) ((symlinkMode >>> 8) & 0xff);
            return archive;
        }
        throw new AssertionError("ZIP central directory not found");
    }

    private int littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private byte[] onePixelPng() {
        return java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private boolean hasIssue(JsonNode preview, String code) {
        for (JsonNode issue : preview.path("errors")) {
            if (code.equals(issue.path("code").asText())) return true;
        }
        return false;
    }

    private Set<String> backgroundFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) return Set.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private String sha256(byte[] bytes) {
        return com.example.nav.module.datapackage.service.PortableDataSnapshotService.sha256(bytes);
    }

    private User insertUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("Portable!Pass2026"));
        user.setNickname(username);
        user.setRole(role);
        user.setStatus(true);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    private User singleAdmin() {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin").last("LIMIT 1"));
    }

    private SiteConfig singleSite() {
        List<SiteConfig> sites = siteConfigMapper.selectList(null);
        assertEquals(1, sites.size());
        return sites.get(0);
    }

    private String adminToken() {
        return jwtTokenService.createToken(singleAdmin());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static Path createUploadRoot() {
        try {
            return Files.createTempDirectory("portable-package-test-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
