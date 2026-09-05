package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DatabaseSetupServiceGuardIntegrityTest {

    @Test
    void strictReadySchemaAcceptsOnlyTheIdOneSingletonGuard() throws Exception {
        try (Connection connection = readySchema("valid")) {
            execute(connection, "INSERT INTO portable_import_guard (id) VALUES (1)");
            assertDoesNotThrow(() -> validateReadySchema(connection));
        }
    }

    @Test
    void strictReadySchemaRejectsMissingExtraAndCorruptGuardRows() throws Exception {
        try (Connection missing = readySchema("missing")) {
            assertGuardRejected(missing);
        }
        try (Connection extra = readySchema("extra")) {
            execute(extra, "INSERT INTO portable_import_guard (id) VALUES (1), (2)");
            assertGuardRejected(extra);
        }
        try (Connection corrupt = readySchema("corrupt")) {
            execute(corrupt, "INSERT INTO portable_import_guard (id) VALUES (2)");
            assertGuardRejected(corrupt);
        }
    }

    private Connection readySchema(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:guard-" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        execute(connection, """
                CREATE TABLE schema_migration (filename varchar primary key, checksum varchar, applied_at timestamp);
                CREATE TABLE sys_user (id bigint, username varchar, password varchar, role varchar, token_version integer);
                CREATE TABLE site_config (id bigint, install_completed_at timestamp, install_instance_id uuid, version integer);
                CREATE TABLE portable_import_guard (id integer primary key);
                CREATE TABLE portable_import_operation (job_id varchar, preview_token varchar, user_id bigint,
                    committed_at timestamp, site_version integer);
                CREATE TABLE nav_category (id bigint, name varchar, sort_order integer, visible boolean);
                CREATE TABLE nav_bookmark (id bigint, category_id bigint, name varchar, url varchar,
                    sort_order integer, visible boolean);
                CREATE TABLE search_engine (id bigint, name varchar, search_url varchar, is_default boolean, visible boolean);
                CREATE TABLE custom_link (id bigint, title varchar, url varchar, position varchar, visible boolean);
                INSERT INTO schema_migration VALUES
                  ('20260812_0001_postgresql_baseline.sql', '006e38274447656002de06d53f7d4154ba80984388dcb26d1223e636dfce91a6', CURRENT_TIMESTAMP),
                  ('20260814_0002_web_install_state.sql', '7347e9e96d3c2347e1067624b786437f3b509e9d7e7614e773c6b1e067596d86', CURRENT_TIMESTAMP),
                  ('20260815_0003_install_instance_identity.sql', '17df5851046d9a79eb24923b4760f8d0440b15a9a68d1609e2bffe2f1ce280fb', CURRENT_TIMESTAMP),
                  ('20260904_0004_portable_import_operations.sql', '4de5e2df8c8f6780f6d1b25e16ee1dd99b7335c7b7475afb83c63f78cfa7ac63', CURRENT_TIMESTAMP);
                """);
        return connection;
    }

    private void assertGuardRejected(Connection connection) {
        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class, () -> validateReadySchema(connection));
        assertInstanceOf(BusinessException.class, exception.getCause());
    }

    private void validateReadySchema(Connection connection) throws Exception {
        DatabaseSetupService service = new DatabaseSetupService(
                mock(InstallAccessService.class),
                mock(DatabaseConfigurationStore.class),
                mock(DatabaseConnectionTicketStore.class),
                mock(DataSource.class),
                mock(ConfigurableApplicationContext.class),
                new DatabaseInstallProperties());
        Method method = DatabaseSetupService.class.getDeclaredMethod("validateReadySchema", Connection.class);
        method.setAccessible(true);
        method.invoke(service, connection);
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
