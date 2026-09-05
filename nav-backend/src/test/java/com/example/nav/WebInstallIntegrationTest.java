package com.example.nav;

import com.example.nav.common.config.WebInstallProperties;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:web-install-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "nav.bootstrap.enabled=false",
        "nav.web-install.enabled=true",
        "nav.database-install.allow-insecure-setup=false",
        "nav.database-install.trust-forwarded-https=false",
        "nav.upload.directory=${java.io.tmpdir}/yunlume-install-test",
        "spring.cache.type=simple"
})
@AutoConfigureMockMvc
class WebInstallIntegrationTest {

    private static final String STATUS_URL = "/api/install/status";
    private static final String COMPLETE_URL = "/api/install/complete";
    private static final String STRONG_PASSWORD = "Cedar!River2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WebInstallProperties webInstallProperties;

    @BeforeEach
    void prepareFreshInstallation() {
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM site_config");
        jdbcTemplate.update("""
                INSERT INTO site_config (
                    id, site_name, site_description, background_type, background_color,
                    font_color, background_effect, music_enabled, subscribe_enabled,
                    top_content_enabled, version
                ) VALUES (1, ?, ?, 'color', '#050505', '#ffffff', FALSE, FALSE, FALSE, TRUE, 0)
                """, "Uninstalled", "Waiting for installation");
    }

    @AfterEach
    void removeInstallationData() {
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM site_config");
    }

    @Test
    void refusesHttpAdministratorCredentialsBeforeAnyInstallationWrite() throws Exception {
        mockMvc.perform(post(COMPLETE_URL)
                        .header("X-Forwarded-Proto", "https")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "siteName", "Should not be installed",
                                "username", "first-admin", "nickname", "管理员",
                                "password", STRONG_PASSWORD, "confirmPassword", STRONG_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("HTTPS")));
        assertEquals(0L, userMapper.selectCount(null));
        assertEquals("Uninstalled", jdbcTemplate.queryForObject("SELECT site_name FROM site_config", String.class));
    }

    @Test
    void statusIsPublicAndReturnsOnlyMinimalInstallationState() throws Exception {
        mockMvc.perform(get(STATUS_URL).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REQUIRED"))
                .andExpect(jsonPath("$.data.installationRequired").value(true))
                .andExpect(jsonPath("$.data.webInstallEnabled").value(true))
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.checks").doesNotExist());

        mockMvc.perform(post("/api/install/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.checks.database.ok").value(true))
                .andExpect(jsonPath("$.data.checks.schema.ok").value(true))
                .andExpect(jsonPath("$.data.checks.siteConfig.ok").value(true))
                .andExpect(jsonPath("$.data.checks.upload.ok").value(true))
                .andExpect(jsonPath("$.data.checks.redis.ok").value(true));
    }

    @Test
    void disabledWebInstallationIsPubliclyReportedAndCannotBeBypassed() throws Exception {
        webInstallProperties.setEnabled(false);
        try {
            mockMvc.perform(get(STATUS_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("DISABLED"))
                    .andExpect(jsonPath("$.data.installationRequired").value(true))
                    .andExpect(jsonPath("$.data.ready").value(false));
            complete("first-admin", STRONG_PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("网页安装功能已关闭"));
            assertEquals(0L, userMapper.selectCount(null));
        } finally {
            webInstallProperties.setEnabled(true);
        }
    }

    @Test
    void passwordAndIdentityPoliciesAreAppliedBeforeAnyWrite() throws Exception {
        complete("admin", "weak-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("至少包含")));

        String unsafeBody = objectMapper.writeValueAsString(Map.of(
                "siteName", "Bad\nName",
                "siteDescription", "Description",
                "username", "admin",
                "nickname", "管理员",
                "password", STRONG_PASSWORD,
                "confirmPassword", STRONG_PASSWORD
        ));
        mockMvc.perform(post(COMPLETE_URL).secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unsafeBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("控制字符或换行")));

        assertEquals(0L, userMapper.selectCount(null));
    }

    @Test
    void successfulInstallationCreatesOneAdminUpdatesSiteAndDoesNotReturnJwt() throws Exception {
        complete("first-admin", STRONG_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installed").value(true))
                .andExpect(jsonPath("$.data.username").doesNotExist())
                .andExpect(jsonPath("$.data.siteName").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());

        User user = userMapper.selectList(null).get(0);
        assertEquals("first-admin", user.getUsername());
        assertEquals("admin", user.getRole());
        assertTrue(Boolean.TRUE.equals(user.getStatus()));
        assertTrue(passwordEncoder.matches(STRONG_PASSWORD, user.getPassword()));
        assertEquals("My Navigation", jdbcTemplate.queryForObject(
                "SELECT site_name FROM site_config", String.class));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT install_completed_at FROM site_config", java.sql.Timestamp.class));

        String loginResponse = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "first-admin",
                                "password", STRONG_PASSWORD
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertTrue(objectMapper.readTree(loginResponse).path("data").path("token").asText().length() > 20);
    }

    @Test
    void completedMarkerPermanentlyRejectsReinstallationEvenIfUsersAreRemoved() throws Exception {
        complete("first-admin", STRONG_PASSWORD)
                .andExpect(status().isOk());
        jdbcTemplate.update("DELETE FROM sys_user");

        mockMvc.perform(get(STATUS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.installationRequired").value(false))
                .andExpect(jsonPath("$.data.ready").value(true));
        complete("second-admin", "Maple!Forest2026")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已经完成安装")));

        assertEquals(0L, userMapper.selectCount(null));
    }

    @Test
    void existingUserAlwaysWinsOverDisabledInstaller() throws Exception {
        User existing = new User();
        existing.setUsername("existing-admin");
        existing.setPassword(passwordEncoder.encode("Existing!Admin2026"));
        existing.setNickname("Existing");
        existing.setRole("admin");
        existing.setStatus(true);
        existing.setTokenVersion(0);
        existing.setCreatedAt(java.time.LocalDateTime.now());
        existing.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.insert(existing);
        String passwordHash = existing.getPassword();
        String siteName = jdbcTemplate.queryForObject("SELECT site_name FROM site_config", String.class);
        webInstallProperties.setEnabled(false);
        try {
            mockMvc.perform(get(STATUS_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.installationRequired").value(false))
                    .andExpect(jsonPath("$.data.ready").value(true));
            assertEquals(passwordHash, userMapper.selectById(existing.getId()).getPassword());
            assertEquals(siteName, jdbcTemplate.queryForObject("SELECT site_name FROM site_config", String.class));
        } finally {
            webInstallProperties.setEnabled(true);
        }
    }

    @Test
    void failedSiteFinalizationRollsBackTheInsertedAdministrator() throws Exception {
        jdbcTemplate.execute("""
                ALTER TABLE site_config
                ADD CONSTRAINT chk_test_install_stays_null
                CHECK (install_completed_at IS NULL)
        """);
        try {
            complete("rollback-admin", STRONG_PASSWORD)
                    .andExpect(status().isConflict());
            assertEquals(0L, userMapper.selectCount(null));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL", Integer.class));
        } finally {
            jdbcTemplate.execute("ALTER TABLE site_config DROP CONSTRAINT chk_test_install_stays_null");
        }
    }

    @Test
    void concurrentCompletionAllowsExactlyOneAdministrator() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> first = concurrentComplete(
                ready, start, "parallel-one", "Cedar!River2026");
        CompletableFuture<Integer> second = concurrentComplete(
                ready, start, "parallel-two", "Maple!Forest2026");

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        int firstStatus = first.get(10, TimeUnit.SECONDS);
        int secondStatus = second.get(10, TimeUnit.SECONDS);

        assertEquals(200, Math.min(firstStatus, secondStatus));
        assertEquals(409, Math.max(firstStatus, secondStatus));
        assertEquals(1L, userMapper.selectCount(null));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL", Integer.class));
    }

    @Test
    void unknownInstallSubpathsAreNotPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/install/internal-diagnostics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void protectedEnvironmentCheckRejectsAPartiallyInitializedSchemaWithoutWriting() throws Exception {
        jdbcTemplate.execute("ALTER TABLE custom_link RENAME TO custom_link_incomplete");
        try {
            mockMvc.perform(get(STATUS_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("REQUIRED"));
            mockMvc.perform(post("/api/install/check"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ready").value(false))
                    .andExpect(jsonPath("$.data.checks.schema.ok").value(false));
            complete("partial-schema-admin", STRONG_PASSWORD)
                    .andExpect(status().isServiceUnavailable());
            assertEquals(0L, userMapper.selectCount(null));
        } finally {
            jdbcTemplate.execute("ALTER TABLE custom_link_incomplete RENAME TO custom_link");
        }
    }

    @Test
    void enabledInstallerCanDiagnoseMissingSiteSingletonButCompletionRemainsFailClosed() throws Exception {
        jdbcTemplate.update("DELETE FROM site_config");

        mockMvc.perform(get(STATUS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REQUIRED"))
                .andExpect(jsonPath("$.data.installationRequired").value(true))
                .andExpect(jsonPath("$.data.ready").value(true));
        mockMvc.perform(post("/api/install/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.checks.siteConfig.ok").value(false));
        complete("missing-site-config-admin", STRONG_PASSWORD)
                .andExpect(status().isServiceUnavailable());
        assertEquals(0L, userMapper.selectCount(null));
    }

    private CompletableFuture<Integer> concurrentComplete(
            CountDownLatch ready,
            CountDownLatch start,
            String username,
            String password
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent installation start timed out");
                }
                MvcResult result = complete(username, password)
                        .andReturn();
                return result.getResponse().getStatus();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private org.springframework.test.web.servlet.ResultActions complete(
            String username,
            String password
    ) throws Exception {
        var request = post(COMPLETE_URL).secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "siteName", "My Navigation",
                        "siteDescription", "A freshly installed navigation site",
                        "username", username,
                        "nickname", "管理员",
                        "password", password,
                        "confirmPassword", password
                )));
        return mockMvc.perform(request);
    }
}
