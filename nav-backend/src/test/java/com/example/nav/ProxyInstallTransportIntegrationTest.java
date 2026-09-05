package com.example.nav;

import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 保留真实 Spring Security/CORS/Controller/安装事务，仅隔离数据库和安装文件目录。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:proxy-install-transport;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=0",
        "nav.bootstrap.enabled=false",
        "nav.web-install.enabled=true",
        "nav.database-install.source=LEGACY_ENV",
        "nav.redis-install.source=LEGACY_ENV",
        "nav.database-install.allow-insecure-setup=false",
        "nav.database-install.trust-forwarded-https=true",
        "nav.database-install.trusted-proxy-peers=127.0.0.1",
        "nav.cors.allowed-origins=http://localhost:5173",
        "nav.upload.cleanup-initial-delay-ms=3600000",
        "spring.cache.type=simple"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProxyInstallTransportIntegrationTest {
    private static final Path ROOT = createRoot();
    private static final String HOST = "navigation.example.test:8443";
    private static final String ORIGIN = "https://" + HOST;
    private static final String PASSWORD = "Cedar!River2026";
    private static final String COMPLETE = "/api/install/complete";

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) {
        registry.add("nav.upload.directory", () -> ROOT.resolve("uploads").toString());
        for (String kind : new String[]{"database", "redis"}) {
            String prefix = "nav." + kind + "-install.";
            registry.add(prefix + "config-file", () -> ROOT.resolve(kind + ".properties").toString());
            registry.add(prefix + "configured-marker-file", () -> ROOT.resolve(kind + ".configured").toString());
            registry.add(prefix + "completed-marker-file", () -> ROOT.resolve(kind + ".completed").toString());
            registry.add(prefix + "ca-certificate-file", () -> ROOT.resolve(kind + "-ca.pem").toString());
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void freshInstallation() {
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM site_config");
        jdbc.update("""
                INSERT INTO site_config (
                    id, site_name, site_description, background_type, background_color,
                    font_color, background_effect, music_enabled, subscribe_enabled,
                    top_content_enabled, version
                ) VALUES (1, 'Uninstalled', 'Waiting', 'color', '#050505', '#ffffff',
                    FALSE, FALSE, FALSE, TRUE, 0)
                """);
    }

    @AfterAll
    static void removeFiles() throws IOException {
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void trustedHttpsProxyCanCompleteInstallationAndLoginWithBrowserOrigin() throws Exception {
        mvc.perform(transport(post(COMPLETE), "127.0.0.1", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(installation()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.data.installed").value(true));

        assertEquals(1L, users.selectCount(null));
        User user = users.selectList(null).get(0);
        assertEquals("proxy-admin", user.getUsername());
        assertEquals("admin", user.getRole());
        assertTrue(passwords.matches(PASSWORD, user.getPassword()));
        assertEquals("Proxy Site", jdbc.queryForObject("SELECT site_name FROM site_config", String.class));

        mvc.perform(transport(post("/api/admin/auth/login"), "127.0.0.1", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "proxy-admin", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"database/test", "database/configure", "redis/test", "redis/configure"})
    void trustedProxySetupPostsReachTheirBusinessStateChecks(String endpoint) throws Exception {
        String body = endpoint.endsWith("/configure")
                ? json.writeValueAsString(Map.of("connectionTicket", "0".repeat(64))) : "{}";
        String expectedMessage = endpoint.startsWith("database/")
                ? "数据库连接已由运行配置管理，断线时不能通过安装向导改库"
                : "请先完成并验证数据库配置";
        // 使用真实业务前置拒绝作为到达服务层的证据，不连接外部数据库/Redis，也不写运行配置。
        mvc.perform(transport(post("/api/install/" + endpoint), "127.0.0.1", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(expectedMessage));
        assertUninstalled();
    }

    @Test
    void trustedBrowserPreflightPassesThroughTheRealSecurityChain() throws Exception {
        mvc.perform(transport(options(COMPLETE), "127.0.0.1", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
        assertUninstalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://navigation.example.test:8443"})
    void untrustedPeerCannotCreateAdminBySpoofingForwardedHttpsEvenWhenCorsAllowsTheRequest(String origin)
            throws Exception {
        mvc.perform(transport(post(COMPLETE), "192.0.2.8", origin)
                        .contentType(MediaType.APPLICATION_JSON).content(installation()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("HTTPS")));
        assertUninstalled();
    }

    @Test
    void untrustedPeerDoesNotGetTheTrustedProxyCorsException() throws Exception {
        mvc.perform(transport(post(COMPLETE), "192.0.2.8", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content(installation()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(content().string(containsString("Invalid CORS request")));
        assertUninstalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example.test:8443", "https://navigation.example.test", "https://navigation.example.test:9443"
    })
    void trustedProxyStillRejectsForeignHostsAndPorts(String origin) throws Exception {
        mvc.perform(transport(post(COMPLETE), "127.0.0.1", origin)
                        .contentType(MediaType.APPLICATION_JSON).content(installation()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(content().string(containsString("Invalid CORS request")));
        assertUninstalled();
    }

    private MockHttpServletRequestBuilder transport(MockHttpServletRequestBuilder request, String peer, String origin) {
        request.secure(false).header("Host", HOST).header("X-Forwarded-Proto", "https")
                .with(servlet -> {
                    servlet.setRemoteAddr(peer);
                    servlet.setScheme("http");
                    servlet.setServerName("navigation.example.test");
                    servlet.setServerPort(8443);
                    return servlet;
                });
        if (!origin.isEmpty()) request.header("Origin", origin);
        return request;
    }

    private String installation() throws IOException {
        return json.writeValueAsString(Map.of("siteName", "Proxy Site", "username", "proxy-admin",
                "nickname", "管理员", "password", PASSWORD, "confirmPassword", PASSWORD));
    }

    private void assertUninstalled() {
        assertEquals(0L, users.selectCount(null));
        assertEquals("Uninstalled", jdbc.queryForObject("SELECT site_name FROM site_config", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL", Integer.class));
    }

    private static Path createRoot() {
        try {
            return Files.createTempDirectory("proxy-install-transport-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
