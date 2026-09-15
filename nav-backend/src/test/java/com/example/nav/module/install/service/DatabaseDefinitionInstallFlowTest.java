package com.example.nav.module.install.service;

import com.example.nav.NavApplication;
import com.example.nav.common.config.WebInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.install.dto.InstallCompleteDTO;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.upload.config.UploadStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 独占外部创建的 PostgreSQL 空库，真实安装服务与事务代理均不替换。 */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INSTALL_FLOW_TEST_URL", matches = ".+")
class DatabaseDefinitionInstallFlowTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installationRechecksDefinitionsAfterEnvironmentCheckAndInsideCompletionTransaction() throws Exception {
        String url = requiredEnvironment("POSTGRESQL_INSTALL_FLOW_TEST_URL");
        assertTrue(url.matches("jdbc:postgresql://[^/?#]+/yunlume_platform_test_installflow_pg(?:14|18)"),
                "安装流程回归仅允许命名明确的一次性 PostgreSQL 数据库");
        var datasource = new DriverManagerDataSource(url,
                requiredEnvironment("POSTGRESQL_INSTALL_FLOW_TEST_USERNAME"),
                requiredEnvironment("POSTGRESQL_INSTALL_FLOW_TEST_PASSWORD"));
        initializeEmptyDatabase(datasource);
        var observer = new JdbcTemplate(datasource);

        try (ConfigurableApplicationContext context = startApplication(url)) {
            var service = context.getBean(InstallService.class);
            var transactionService = context.getBean(InstallTransactionService.class);
            assertTrue(AopUtils.isAopProxy(transactionService), "必须执行实际 Spring 安装事务");
            assertReady(service);
            var originalSite = observer.queryForMap("SELECT * FROM public.site_config");

            // 修改名称不变的实际定义，独立连接立即提交；旧检查结果不能授权后续写入。
            int driftNumber = 0;
            for (Drift drift : definitionDrifts()) {
                assertReady(service);
                observer.execute(drift.change());
                try {
                    var check = service.check();
                    assertFalse(check.ready(), drift.name());
                    assertFalse(check.checks().schema().ok(), drift.name());
                    BusinessException failure = assertThrows(BusinessException.class,
                            () -> service.complete(command()), drift.name());
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus(), drift.name());
                    if (driftNumber++ == 0) assertHttpRejection(context);
                    assertUnchangedInstallation(observer, originalSite);
                } finally {
                    observer.execute(drift.restore());
                }
                assertReady(service);
            }

            assertUuidGateAndRollback(context, observer, originalSite);

            // 外层环境检查通过后，编码密码这一已有边界处由另一连接提交定义漂移。
            // 完成事务必须重新校验，不能仅依赖 HTTP /check 或 complete 的事务外检查。
            AtomicInteger encodes = new AtomicInteger();
            PasswordEncoder originalEncoder = context.getBean(PasswordEncoder.class);
            PasswordEncoder driftDuringEncoding = new PasswordEncoder() {
                @Override
                public String encode(CharSequence rawPassword) {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "漂移必须发生在实际完成事务开始之前");
                    if (encodes.incrementAndGet() == 1) {
                        observer.execute("ALTER TABLE public.nav_category ALTER COLUMN name TYPE varchar(51)");
                    }
                    return originalEncoder.encode(rawPassword);
                }

                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    return originalEncoder.matches(rawPassword, encodedPassword);
                }
            };
            var guardedCompletion = new InstallService(
                    context.getBean(WebInstallProperties.class), context.getBean(UploadStorageProperties.class),
                    context.getBean(JdbcTemplate.class), context.getBeanProvider(StringRedisTemplate.class),
                    "simple", driftDuringEncoding, transactionService,
                    context.getBean(InstallAccessService.class), context.getBean(DatabaseConfigurationStore.class),
                    context.getBean(DatabaseIdentityService.class), context.getBean(RedisConfigurationStore.class), "");

            assertReady(guardedCompletion);
            try {
                BusinessException failure = assertThrows(BusinessException.class,
                        () -> guardedCompletion.complete(command()));
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
                assertEquals(1, encodes.get(), "必须越过外层环境检查并触发真实事务前的漂移");
                assertUnchangedInstallation(observer, originalSite);
            } finally {
                observer.execute("ALTER TABLE public.nav_category ALTER COLUMN name TYPE varchar(50)");
            }

            assertReady(guardedCompletion);
            assertTrue(guardedCompletion.complete(command()).installed());
            assertEquals(2, encodes.get());
            assertEquals(1L, observer.queryForObject("SELECT count(*) FROM public.sys_user", Long.class));
            assertEquals(1L, observer.queryForObject(
                    "SELECT count(*) FROM public.site_config WHERE install_completed_at IS NOT NULL", Long.class));
            assertEquals("Definition verification", observer.queryForObject(
                    "SELECT site_name FROM public.site_config", String.class));
            assertTrue(originalEncoder.matches("Cedar!River2026", observer.queryForObject(
                    "SELECT password FROM public.sys_user WHERE username = 'definition-admin'", String.class)));
            BusinessException duplicate = assertThrows(BusinessException.class,
                    () -> guardedCompletion.complete(command()));
            assertEquals(HttpStatus.CONFLICT, duplicate.getStatus());
            assertEquals(1L, observer.queryForObject("SELECT count(*) FROM public.sys_user", Long.class));
        }
    }

    private void initializeEmptyDatabase(DriverManagerDataSource datasource) throws Exception {
        try (Connection connection = datasource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getCatalog().matches("yunlume_platform_test_installflow_pg(?:14|18)"));
            try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                    SELECT count(*) FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                    """)) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1), "拒绝修改非空测试数据库");
            }
            String canonical = new ClassPathResource("schema-postgresql.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            var strict = DatabaseSetupService.class.getDeclaredMethod("strictInitializationScript", String.class);
            strict.setAccessible(true);
            var installer = new DatabaseSetupService(null, null, null, datasource, null,
                    new com.example.nav.common.config.DatabaseInstallProperties());
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute((String) strict.invoke(installer, canonical));
                connection.commit();
            }
        }
    }

    private void assertUnchangedInstallation(JdbcTemplate observer, java.util.Map<String, Object> originalSite) {
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM public.sys_user", Long.class));
        assertEquals(originalSite, observer.queryForMap("SELECT * FROM public.site_config"),
                "拒绝安装不能写入站点字段、版本或数据库完成标记");
        assertFalse(Files.exists(temporaryDirectory.resolve("config/install.completed")),
                "拒绝安装不能生成本地完成标记");
    }

    private void assertUuidGateAndRollback(ConfigurableApplicationContext context, JdbcTemplate observer,
                                          java.util.Map<String, Object> originalSite) {
        SiteConfigMapper mapper = context.getBean(SiteConfigMapper.class);
        JdbcTemplate transactionalJdbc = context.getBean(JdbcTemplate.class);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            try {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                UUID expected = UUID.fromString(transactionalJdbc.queryForObject(
                        "SELECT install_instance_id::text FROM public.site_config WHERE id = 1", String.class));
                UUID wrong = new UUID(expected.getMostSignificantBits() ^ 1L, expected.getLeastSignificantBits());
                assertNotEquals(expected, wrong);
                LocalDateTime completedAt = LocalDateTime.now();

                assertEquals(0, mapper.completeInstallation(1L, "Wrong identity", "Must remain unchanged",
                        completedAt, wrong), "错误的非空 UUID 不能更新站点");
                assertEquals(originalSite, transactionalJdbc.queryForMap("SELECT * FROM public.site_config"));
                assertEquals(1, mapper.completeInstallation(1L, "Matching identity", "Rolled back by this test",
                        completedAt, expected), "正确的非空 UUID 必须恰好更新一行");
                assertEquals("Matching identity", transactionalJdbc.queryForObject(
                        "SELECT site_name FROM public.site_config", String.class));
                assertEquals(1L, transactionalJdbc.queryForObject(
                        "SELECT count(*) FROM public.site_config WHERE install_completed_at IS NOT NULL", Long.class));
                assertEquals(0L, transactionalJdbc.queryForObject("SELECT count(*) FROM public.sys_user", Long.class));
            } finally {
                // 本段只验证 SQL 身份门禁，不能影响后续 null 身份的真实完成安装流程。
                status.setRollbackOnly();
            }
        });
        assertUnchangedInstallation(observer, originalSite);
    }

    private void assertReady(InstallService service) {
        var result = service.check();
        assertTrue(result.ready(), () -> "恢复后的真实安装检查应通过: " + result);
        assertTrue(result.checks().schema().ok());
    }

    private void assertHttpRejection(ConfigurableApplicationContext context) throws Exception {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String base = "http://127.0.0.1:" + port;
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).build();
        var checkResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/install/check"))
                        .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, checkResponse.statusCode());
        var check = mapper.readTree(checkResponse.body());
        assertEquals(200, check.path("code").asInt());
        assertTrue(check.path("data").path("ready").isBoolean());
        assertFalse(check.path("data").path("ready").booleanValue());
        assertTrue(check.path("data").path("checks").path("schema").path("ok").isBoolean());
        assertFalse(check.path("data").path("checks").path("schema").path("ok").booleanValue());

        String body = mapper.writeValueAsString(command());
        // 不停用实际安全门禁；仅模拟配置中受信任的本机 HTTPS 代理。
        var completeRequest = HttpRequest.newBuilder(URI.create(base + "/api/install/complete"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        var insecureResponse = client.send(completeRequest.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, insecureResponse.statusCode());
        assertEquals(403, mapper.readTree(insecureResponse.body()).path("code").asInt());

        var completeResponse = client.send(completeRequest.header("X-Forwarded-Proto", "https").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(503, completeResponse.statusCode());
        var rejected = mapper.readTree(completeResponse.body());
        assertEquals(503, rejected.path("code").asInt());
        assertFalse(rejected.path("data").path("installed").asBoolean());
    }

    private ConfigurableApplicationContext startApplication(String url) {
        String config = temporaryDirectory.resolve("config").toAbsolutePath().toString().replace('\\', '/');
        SpringApplication application = new SpringApplication(NavApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        return application.run(
                "--spring.profiles.active=local", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + url,
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.datasource.username=" + requiredEnvironment("POSTGRESQL_INSTALL_FLOW_TEST_USERNAME"),
                "--spring.datasource.password=" + requiredEnvironment("POSTGRESQL_INSTALL_FLOW_TEST_PASSWORD"),
                "--spring.sql.init.mode=never", "--spring.cache.type=simple",
                "--nav.bootstrap.enabled=false", "--nav.bootstrap.demo-data-enabled=false",
                "--nav.web-install.enabled=true", "--nav.database-install.source=LEGACY_ENV",
                "--nav.database-install.allow-insecure-setup=false",
                "--nav.database-install.trust-forwarded-https=true",
                "--nav.database-install.trusted-proxy-peers=127.0.0.1",
                "--nav.database-install.config-file=" + config + "/database.properties",
                "--nav.database-install.configured-marker-file=" + config + "/database.configured",
                "--nav.database-install.completed-marker-file=" + config + "/install.completed",
                "--nav.database-install.ca-certificate-file=" + config + "/postgresql-ca.pem",
                "--nav.redis-install.source=LEGACY_ENV",
                "--nav.redis-install.config-file=" + config + "/redis.properties",
                "--nav.redis-install.configured-marker-file=" + config + "/redis.configured",
                "--nav.redis-install.ca-certificate-file=" + config + "/redis-ca.pem",
                "--nav.upload.directory=" + config + "/uploads", "--logging.level.root=ERROR");
    }

    private InstallCompleteDTO command() {
        return new InstallCompleteDTO("Definition verification", "Retry after restoring the schema",
                "definition-admin", "管理员", "Cedar!River2026", "Cedar!River2026");
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "缺少 " + name);
        assertFalse(value.isBlank(), "缺少 " + name);
        return value;
    }

    private List<Drift> definitionDrifts() {
        return List.of(
                new Drift("同名列长度改变",
                        "ALTER TABLE public.nav_category ALTER COLUMN name TYPE varchar(51)",
                        "ALTER TABLE public.nav_category ALTER COLUMN name TYPE varchar(50)"),
                new Drift("同名索引键顺序改变",
                        "DROP INDEX public.idx_nav_category_sort; CREATE INDEX idx_nav_category_sort ON public.nav_category (id, sort_order)",
                        "DROP INDEX public.idx_nav_category_sort; CREATE INDEX idx_nav_category_sort ON public.nav_category (sort_order, id)"),
                new Drift("同名检查约束语义改变",
                        "ALTER TABLE public.custom_link DROP CONSTRAINT chk_custom_link_position; ALTER TABLE public.custom_link ADD CONSTRAINT chk_custom_link_position CHECK (position IN ('header', 'footer', 'side'))",
                        "ALTER TABLE public.custom_link DROP CONSTRAINT chk_custom_link_position; ALTER TABLE public.custom_link ADD CONSTRAINT chk_custom_link_position CHECK (position IN ('header', 'footer'))"),
                new Drift("identity 序列增量改变",
                        "ALTER SEQUENCE public.nav_category_id_seq INCREMENT BY 2",
                        "ALTER SEQUENCE public.nav_category_id_seq INCREMENT BY 1"),
                new Drift("同名触发器被禁用",
                        "ALTER TABLE public.nav_category DISABLE TRIGGER trg_nav_category_updated_at",
                        "ALTER TABLE public.nav_category ENABLE TRIGGER trg_nav_category_updated_at"));
    }

    private record Drift(String name, String change, String restore) {}
}
