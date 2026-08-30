package com.example.nav;

import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.dto.InstallCompleteDTO;
import com.example.nav.module.install.service.InstallService;
import com.example.nav.module.install.vo.InstallStatusVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartedWebInstallStateIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void completedInstallationSurvivesApplicationContextRestart() {
        String databasePath = normalizedPath(temporaryDirectory.resolve("yunlume-restart-test"));

        try (ConfigurableApplicationContext context = startApplication(databasePath)) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            insertFreshSiteConfig(jdbcTemplate);

            InstallService installService = context.getBean(InstallService.class);
            installService.complete(new InstallCompleteDTO(
                    "Yunlume",
                    "Restart persistence test",
                    "first-admin",
                    "管理员",
                    "Cedar!River2026",
                    "Cedar!River2026"
            ));

            assertEquals("COMPLETED", installService.status().state());
        }

        try (ConfigurableApplicationContext context = startApplication(databasePath)) {
            InstallService installService = context.getBean(InstallService.class);
            InstallStatusVO status = installService.status();

            assertEquals("COMPLETED", status.state());
            assertFalse(status.installationRequired());
            assertTrue(status.ready());

            BusinessException exception = assertThrows(BusinessException.class, () ->
                    installService.complete(new InstallCompleteDTO(
                            "Yunlume Again",
                            "Duplicate installation attempt",
                            "second-admin",
                            "第二管理员",
                            "Maple!Forest2026",
                            "Maple!Forest2026"
                    )));
            assertEquals(HttpStatus.CONFLICT, exception.getStatus());

            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_user", Integer.class));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL",
                    Integer.class));
        }
    }

    private ConfigurableApplicationContext startApplication(String databasePath) {
        String configPath = normalizedPath(temporaryDirectory.resolve("config"));
        SpringApplication application = new SpringApplication(NavApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        return application.run(
                "--spring.profiles.active=local",
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:file:" + databasePath
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.sql.init.mode=always",
                "--nav.bootstrap.enabled=false",
                "--nav.bootstrap.demo-data-enabled=false",
                "--nav.web-install.enabled=true",
                "--nav.database-install.source=LEGACY_ENV",
                "--nav.database-install.config-file=" + configPath + "/database.properties",
                "--nav.database-install.configured-marker-file=" + configPath + "/database.configured",
                "--nav.database-install.completed-marker-file=" + configPath + "/install.completed",
                "--nav.database-install.ca-certificate-file=" + configPath + "/postgresql-ca.pem",
                "--nav.redis-install.source=LEGACY_ENV",
                "--nav.redis-install.config-file=" + configPath + "/redis.properties",
                "--nav.redis-install.configured-marker-file=" + configPath + "/redis.configured",
                "--nav.redis-install.ca-certificate-file=" + configPath + "/redis-ca.pem",
                "--spring.cache.type=simple",
                "--nav.upload.directory=" + configPath + "/uploads",
                "--logging.level.root=ERROR"
        );
    }

    private void insertFreshSiteConfig(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                INSERT INTO site_config (
                    site_name, site_description, background_type, background_color,
                    font_color, background_effect, music_enabled, subscribe_enabled,
                    top_content_enabled, version
                ) VALUES (?, ?, 'color', '#050505', '#ffffff', FALSE, FALSE, FALSE, TRUE, 0)
                """, "Uninstalled", "Waiting for installation");
    }

    private String normalizedPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
