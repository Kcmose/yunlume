package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.config.PostgresqlMigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 仅针对调用者创建的专用空库；实例生命周期和删除由外部测试进程负责。 */
@EnabledIfEnvironmentVariable(named = "POSTGRESQL_CATALOG_TEST_URL", matches = ".+")
class PostgresqlCatalogCompatibilityTest {
    @Test
    void realPostgresqlInstallerAndMigrationRejectCatalogDrift() throws Exception {
        var datasource = new DriverManagerDataSource(System.getenv("POSTGRESQL_CATALOG_TEST_URL"),
                System.getenv("POSTGRESQL_CATALOG_TEST_USERNAME"),
                System.getenv("POSTGRESQL_CATALOG_TEST_PASSWORD"));
        var installer = new DatabaseSetupService(null, null, null, datasource, null,
                new DatabaseInstallProperties());
        var migration = new PostgresqlMigrationRunner(datasource, mock(DatabaseConfigurationStore.class));
        try (Connection connection = datasource.getConnection()) {
            assertTrue(connection.getCatalog().matches("yunlume_platform_test_[a-z0-9_]+"),
                    "catalog test requires an explicitly named disposable database");
            try (var result = connection.createStatement().executeQuery("""
                    SELECT count(*) FROM pg_catalog.pg_class c
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public'
                    """)) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1), "refusing to modify a nonempty test database");
            }
            assertTrue(inspect(installer, connection).contains("state=EMPTY"));
            String canonical = new ClassPathResource("schema-postgresql.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            var strict = DatabaseSetupService.class.getDeclaredMethod("strictInitializationScript", String.class);
            strict.setAccessible(true);
            connection.setAutoCommit(false);
            execute(connection, (String) strict.invoke(installer, canonical));
            assertReady(installer, connection);
            connection.commit();
            connection.setAutoCommit(true);
            assertDoesNotThrow(migration::afterPropertiesSet);

            // 同名但不兼容的列不能被首次安装接管；每次回滚漂移，保留权威基线。
            for (String drift : new String[]{
                    "ALTER TABLE public.nav_bookmark ALTER COLUMN url TYPE varchar(1) USING left(url, 1)",
                    "ALTER TABLE public.nav_category ALTER COLUMN sort_order TYPE bigint",
                    "ALTER TABLE public.nav_category ALTER COLUMN visible SET DEFAULT false",
                    "ALTER TABLE public.sys_user ALTER COLUMN id SET GENERATED ALWAYS",
                    "ALTER TABLE public.nav_bookmark ALTER COLUMN url TYPE varchar(500) COLLATE \"C\"",
                    "ALTER TABLE public.nav_bookmark ALTER COLUMN created_at TYPE timestamp(0)"
            }) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, drift);
                    assertThrows(InvocationTargetException.class, () -> inspect(installer, connection), drift);
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                assertReady(installer, connection);
            }

            // 从 0003 升级而来：执行真实固定迁移，再验证幂等启动。
            execute(connection, """
                    DROP TABLE public.portable_import_operation;
                    DROP TABLE public.portable_import_guard;
                    ALTER TABLE public.site_config DROP CONSTRAINT chk_site_config_version_range;
                    DELETE FROM public.schema_migration
                      WHERE filename = '20260904_0004_portable_import_operations.sql';
                    """);
            assertDoesNotThrow(migration::afterPropertiesSet);
            assertReady(installer, connection);
            assertDoesNotThrow(migration::afterPropertiesSet);

            execute(connection, "ALTER TABLE public.schema_migration ALTER COLUMN checksum DROP NOT NULL");
            assertRejected(installer, connection, migration);
            execute(connection, "ALTER TABLE public.schema_migration ALTER COLUMN checksum SET NOT NULL");
            assertReady(installer, connection);

            // 新增非空约束也属于未知结构，不能因排除 contype=n 而默许。
            execute(connection, "ALTER TABLE public.site_config ALTER COLUMN site_description SET NOT NULL");
            assertThrows(InvocationTargetException.class, () -> inspect(installer, connection));
            execute(connection, "ALTER TABLE public.site_config ALTER COLUMN site_description DROP NOT NULL");
            execute(connection, "ALTER TABLE public.portable_import_guard ADD CONSTRAINT unknown_extra CHECK (id > 0)");
            assertRejected(installer, connection, migration);
            execute(connection, "ALTER TABLE public.portable_import_guard DROP CONSTRAINT unknown_extra");

            if (connection.getMetaData().getDatabaseMajorVersion() >= 18) {
                execute(connection, """
                        ALTER TABLE public.schema_migration ALTER COLUMN checksum DROP NOT NULL;
                        ALTER TABLE public.schema_migration ADD CONSTRAINT checksum_not_valid
                          NOT NULL checksum NOT VALID;
                        """);
                assertRejected(installer, connection, migration);
                execute(connection, "ALTER TABLE public.schema_migration VALIDATE CONSTRAINT checksum_not_valid");
                assertReady(installer, connection);
                assertDoesNotThrow(migration::afterPropertiesSet);

                execute(connection, "ALTER TABLE public.schema_migration ALTER CONSTRAINT checksum_not_valid NO INHERIT");
                assertRejected(installer, connection, migration);
                execute(connection, "ALTER TABLE public.schema_migration ALTER CONSTRAINT checksum_not_valid INHERIT");
                execute(connection, """
                        ALTER TABLE public.schema_migration DROP CONSTRAINT chk_schema_migration_checksum;
                        ALTER TABLE public.schema_migration ADD CONSTRAINT chk_schema_migration_checksum
                          CHECK (checksum ~ '^[0-9a-f]{64}$') NOT ENFORCED;
                        """);
                assertRejected(installer, connection, migration);
                execute(connection, """
                        ALTER TABLE public.schema_migration DROP CONSTRAINT chk_schema_migration_checksum;
                        ALTER TABLE public.schema_migration ADD CONSTRAINT chk_schema_migration_checksum
                          CHECK (checksum ~ '^[0-9a-f]{64}$');
                        """);
            }
            assertReady(installer, connection);
            assertDoesNotThrow(migration::afterPropertiesSet);
        }
    }

    private void assertRejected(DatabaseSetupService service, Connection connection,
                                PostgresqlMigrationRunner migration) {
        assertThrows(InvocationTargetException.class, () -> inspect(service, connection));
        assertThrows(RuntimeException.class, migration::afterPropertiesSet);
    }

    private void assertReady(DatabaseSetupService service, Connection connection) throws Exception {
        assertTrue(inspect(service, connection).contains("state=READY_UNINSTALLED"));
    }

    private String inspect(DatabaseSetupService service, Connection connection) throws Exception {
        var method = DatabaseSetupService.class.getDeclaredMethod("inspectConnection", Connection.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(service, connection, false).toString();
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
