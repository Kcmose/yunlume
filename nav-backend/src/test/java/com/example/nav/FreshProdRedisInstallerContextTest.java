package com.example.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FreshProdRedisInstallerContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshProductionContextStartsWithBlankRedisEnvironment() {
        SpringApplication application = new SpringApplication(NavApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        String config = temporaryDirectory.toString();
        try (ConfigurableApplicationContext context = application.run(
                "--spring.profiles.active=prod",
                "--server.port=0",
                "--CACHE_TYPE=redis",
                "--spring.cache.type=redis",
                "--REDIS_HOST=",
                "--NAV_REDIS_SOURCE=UNCONFIGURED",
                "--NAV_REDIS_CONFIG_FILE=" + config + "/redis.properties",
                "--NAV_REDIS_CONFIGURED_MARKER_FILE=" + config + "/redis.configured",
                "--NAV_REDIS_CA_FILE=" + config + "/redis-ca.pem",
                "--NAV_DATABASE_SOURCE=UNCONFIGURED",
                "--NAV_DATABASE_CONFIG_FILE=" + config + "/database.properties",
                "--NAV_DATABASE_CONFIGURED_MARKER_FILE=" + config + "/database.configured",
                "--NAV_DATABASE_CA_FILE=" + config + "/postgresql-ca.pem",
                "--NAV_INSTALL_COMPLETED_MARKER_FILE=" + config + "/install.completed",
                "--NAV_BOOTSTRAP_ENABLED=false",
                "--NAV_WEB_INSTALL_ENABLED=true",
                "--JWT_SECRET=" + "fresh-installer-test-secret-32-bytes-minimum-2026",
                "--APP_UPLOAD_DIR=" + config + "/uploads",
                "--logging.level.root=ERROR")) {
            assertEquals("redis.invalid",
                    context.getEnvironment().getProperty("spring.data.redis.host"));
            assertNotNull(context.getBean(RedisConnectionFactory.class));
        }
    }
}
