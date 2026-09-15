package com.example.nav.module.install.service;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.config.PostgresqlMigrationRunner;
import com.example.nav.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            assertTrue(List.of(14, 18).contains(connection.getMetaData().getDatabaseMajorVersion()));
            assertEquals("false:false:false:false:false", scalar(connection, """
                    SELECT rolsuper || ':' || rolcreatedb || ':' || rolcreaterole || ':' ||
                           rolreplication || ':' || rolinherit
                    FROM pg_catalog.pg_roles WHERE rolname = current_user
                    """), "catalog checks must run as the dedicated restricted owner");
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
            Map<String, String> initialized = dataAndSequenceSnapshot(connection);
            assertReady(installer, connection);
            assertEquals(initialized, dataAndSequenceSnapshot(connection), "initial inspection must not write or advance sequences");
            connection.commit();
            connection.setAutoCommit(true);
            assertDoesNotThrow(migration::afterPropertiesSet);
            assertDefinitionDriftRejectedAndRecovered(installer, connection);
            assertInvalidIndexRejectedAndRecovered(installer, connection);
            assertInternalForeignKeyTriggerRejectedAndRecovered(installer, connection);

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

            // 重现历史列追加顺序，再执行真实 0002/0003 和启动时的 0004。
            execute(connection, """
                    DROP TABLE public.portable_import_operation;
                    DROP TABLE public.portable_import_guard;
                    ALTER TABLE public.site_config DROP CONSTRAINT chk_site_config_version_range;
                    ALTER TABLE public.site_config DROP COLUMN install_completed_at;
                    ALTER TABLE public.site_config DROP COLUMN install_instance_id;
                    DELETE FROM public.schema_migration
                      WHERE filename = '20260904_0004_portable_import_operations.sql';
                    """);
            // 历史 0002/0003 只在仓库权威迁移目录；运行 JAR 仅打包启动负责的 0004。
            execute(connection, Files.readString(Path.of("../database/migrations/20260814_0002_web_install_state.sql"),
                    StandardCharsets.UTF_8));
            execute(connection, Files.readString(Path.of("../database/migrations/20260815_0003_install_instance_identity.sql"),
                    StandardCharsets.UTF_8));
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

    private void assertDefinitionDriftRejectedAndRecovered(DatabaseSetupService installer,
                                                           Connection connection) throws Exception {
        List<DefinitionDrift> drifts = List.of(
                new DefinitionDrift("check-expression", """
                        ALTER TABLE public.custom_link DROP CONSTRAINT chk_custom_link_position;
                        ALTER TABLE public.custom_link ADD CONSTRAINT chk_custom_link_position
                          CHECK (position IN ('header', 'footer') AND title <> 'valid-footer');
                        """),
                new DefinitionDrift("check-table-binding", """
                        ALTER TABLE public.custom_link DROP CONSTRAINT chk_custom_link_position;
                        ALTER TABLE public.site_config ADD CONSTRAINT chk_custom_link_position
                          CHECK (site_name IS NOT NULL);
                        """),
                new DefinitionDrift("duplicate-constraint-name", """
                        ALTER TABLE public.site_config ADD CONSTRAINT chk_custom_link_position
                          CHECK (site_name IS NOT NULL);
                        """),
                new DefinitionDrift("check-not-valid", """
                        ALTER TABLE public.custom_link DROP CONSTRAINT chk_custom_link_position;
                        ALTER TABLE public.custom_link ADD CONSTRAINT chk_custom_link_position
                          CHECK (position IN ('header', 'footer')) NOT VALID;
                        """),
                new DefinitionDrift("check-no-inherit", """
                        ALTER TABLE public.custom_link DROP CONSTRAINT chk_custom_link_position;
                        ALTER TABLE public.custom_link ADD CONSTRAINT chk_custom_link_position
                          CHECK (position IN ('header', 'footer')) NO INHERIT;
                        """),
                new DefinitionDrift("foreign-key-action", """
                        ALTER TABLE public.nav_bookmark DROP CONSTRAINT fk_nav_bookmark_category;
                        ALTER TABLE public.nav_bookmark ADD CONSTRAINT fk_nav_bookmark_category
                          FOREIGN KEY (category_id) REFERENCES public.nav_category(id)
                          ON UPDATE CASCADE ON DELETE RESTRICT;
                        """),
                new DefinitionDrift("foreign-key-target", """
                        ALTER TABLE public.nav_bookmark DROP CONSTRAINT fk_nav_bookmark_category;
                        ALTER TABLE public.nav_bookmark ADD CONSTRAINT fk_nav_bookmark_category
                          FOREIGN KEY (category_id) REFERENCES public.nav_bookmark(id)
                          ON UPDATE CASCADE ON DELETE CASCADE;
                        """),
                new DefinitionDrift("unique-deferrable", """
                        ALTER TABLE public.sys_user DROP CONSTRAINT uk_sys_user_username;
                        ALTER TABLE public.sys_user ADD CONSTRAINT uk_sys_user_username
                          UNIQUE (username) DEFERRABLE INITIALLY DEFERRED;
                        """),
                new DefinitionDrift("index-unique", """
                        DROP INDEX public.uk_search_engine_one_visible_default;
                        CREATE INDEX uk_search_engine_one_visible_default ON public.search_engine ((1))
                          WHERE is_default IS TRUE AND visible IS TRUE;
                        """),
                new DefinitionDrift("index-keys", """
                        DROP INDEX public.idx_nav_category_sort;
                        CREATE INDEX idx_nav_category_sort ON public.nav_category (id, sort_order);
                        """),
                new DefinitionDrift("index-sort", """
                        DROP INDEX public.idx_nav_category_sort;
                        CREATE INDEX idx_nav_category_sort ON public.nav_category (sort_order DESC, id);
                        """),
                new DefinitionDrift("index-predicate", """
                        DROP INDEX public.uk_search_engine_one_visible_default;
                        CREATE UNIQUE INDEX uk_search_engine_one_visible_default ON public.search_engine ((1))
                          WHERE is_default IS TRUE;
                        """),
                new DefinitionDrift("index-expression", """
                        DROP INDEX public.uk_search_engine_one_visible_default;
                        CREATE UNIQUE INDEX uk_search_engine_one_visible_default ON public.search_engine ((2))
                          WHERE is_default IS TRUE AND visible IS TRUE;
                        """),
                new DefinitionDrift("index-include", """
                        DROP INDEX public.idx_nav_category_sort;
                        CREATE INDEX idx_nav_category_sort ON public.nav_category (sort_order, id) INCLUDE (name);
                        """),
                new DefinitionDrift("index-opclass", """
                        DROP INDEX public.idx_custom_link_position_sort;
                        CREATE INDEX idx_custom_link_position_sort
                          ON public.custom_link (position varchar_pattern_ops, sort_order, id);
                        """),
                new DefinitionDrift("index-collation", """
                        DROP INDEX public.idx_custom_link_position_sort;
                        CREATE INDEX idx_custom_link_position_sort
                          ON public.custom_link (position COLLATE "C", sort_order, id);
                        """),
                new DefinitionDrift("index-method", """
                        DROP INDEX public.idx_nav_category_sort;
                        CREATE INDEX idx_nav_category_sort ON public.nav_category USING hash (sort_order);
                        """),
                new DefinitionDrift("function-body", """
                        CREATE OR REPLACE FUNCTION public.nav_set_updated_at() RETURNS trigger
                          LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$;
                        """),
                new DefinitionDrift("function-signature", alternateFunctionSignature()),
                new DefinitionDrift("function-security", "ALTER FUNCTION public.nav_set_updated_at() SECURITY DEFINER"),
                new DefinitionDrift("function-strict", "ALTER FUNCTION public.nav_set_updated_at() STRICT"),
                new DefinitionDrift("function-volatility", "ALTER FUNCTION public.nav_set_updated_at() IMMUTABLE"),
                new DefinitionDrift("function-parallel", "ALTER FUNCTION public.nav_set_updated_at() PARALLEL SAFE"),
                new DefinitionDrift("function-config", "ALTER FUNCTION public.nav_set_updated_at() SET search_path TO public"),
                new DefinitionDrift("trigger-disabled", "ALTER TABLE public.site_config DISABLE TRIGGER trg_site_config_updated_at"),
                new DefinitionDrift("trigger-replica", "ALTER TABLE public.site_config ENABLE REPLICA TRIGGER trg_site_config_updated_at"),
                new DefinitionDrift("trigger-timing", replaceSiteTrigger("AFTER UPDATE", "public.site_config", "", "public.nav_set_updated_at()")),
                new DefinitionDrift("trigger-event", replaceSiteTrigger("BEFORE INSERT", "public.site_config", "", "public.nav_set_updated_at()")),
                new DefinitionDrift("trigger-table-binding", replaceSiteTrigger("BEFORE UPDATE", "public.nav_category", "", "public.nav_set_updated_at()")),
                new DefinitionDrift("trigger-function-binding", replaceSiteTrigger("BEFORE UPDATE", "public.site_config", "", "pg_catalog.suppress_redundant_updates_trigger()")),
                new DefinitionDrift("trigger-when", replaceSiteTrigger("BEFORE UPDATE", "public.site_config", "WHEN (NEW.version > 0)", "public.nav_set_updated_at()")),
                new DefinitionDrift("trigger-arguments", replaceSiteTrigger("BEFORE UPDATE", "public.site_config", "", "public.nav_set_updated_at('unexpected')")),
                new DefinitionDrift("duplicate-trigger-name", """
                        CREATE TRIGGER trg_site_config_updated_at BEFORE UPDATE ON public.nav_category
                          FOR EACH ROW EXECUTE FUNCTION public.nav_set_updated_at();
                        """),
                new DefinitionDrift("sequence-identity-binding", """
                        ALTER SEQUENCE public.site_config_id_seq RENAME TO definition_test_temporary_seq;
                        ALTER SEQUENCE public.nav_category_id_seq RENAME TO site_config_id_seq;
                        ALTER SEQUENCE public.definition_test_temporary_seq RENAME TO nav_category_id_seq;
                        """),
                new DefinitionDrift("sequence-increment", "ALTER SEQUENCE public.nav_category_id_seq INCREMENT BY 2"),
                new DefinitionDrift("sequence-cache", "ALTER SEQUENCE public.nav_category_id_seq CACHE 2"),
                new DefinitionDrift("sequence-cycle", "ALTER SEQUENCE public.nav_category_id_seq CYCLE"),
                new DefinitionDrift("sequence-start", "ALTER SEQUENCE public.nav_category_id_seq START WITH 42"),
                new DefinitionDrift("sequence-min", "ALTER SEQUENCE public.nav_category_id_seq MINVALUE 0"),
                new DefinitionDrift("sequence-max", "ALTER SEQUENCE public.nav_category_id_seq MAXVALUE 100000"),
                new DefinitionDrift("sequence-type", "ALTER SEQUENCE public.nav_category_id_seq AS integer"),
                new DefinitionDrift("table-row-security", """
                        ALTER TABLE public.custom_link ENABLE ROW LEVEL SECURITY;
                        CREATE POLICY definition_test_policy ON public.custom_link USING (false);
                        """),
                new DefinitionDrift("table-rewrite-rule", """
                        CREATE RULE definition_test_rule AS ON INSERT TO public.custom_link DO INSTEAD NOTHING;
                        """),
                new DefinitionDrift("table-persistence", "ALTER TABLE public.custom_link SET UNLOGGED"),
                new DefinitionDrift("table-case-collision", "CREATE TABLE public.\"NAV_CATEGORY\" (LIKE public.nav_category)")
        );
        for (DefinitionDrift drift : drifts) {
            Map<String, String> before = dataAndSequenceSnapshot(connection);
            connection.setAutoCommit(false);
            try {
                execute(connection, drift.sql());
                // DDL 和序列改名可能改变快照；比较每次校验前后，区分夹具变更与校验副作用。
                Map<String, String> corrupted = dataAndSequenceSnapshot(connection);
                assertDefinitionRejected(installer, connection, drift.name());
                assertDefinitionRejected(installer, connection, drift.name() + " retry");
                assertEquals(corrupted, dataAndSequenceSnapshot(connection), drift.name() + " rejection wrote data");
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertReady(installer, connection);
            assertReady(installer, connection);
            assertEquals(before, dataAndSequenceSnapshot(connection), drift.name() + " recovery wrote data or advanced a sequence");
            System.out.println("CATALOG_DEFINITION_RECOVERED=" + drift.name());
        }
        System.out.println("CATALOG_DEFINITION_TRANSACTIONAL_CASES=" + drifts.size());
    }

    private String replaceSiteTrigger(String event, String table, String condition, String function) {
        return "DROP TRIGGER trg_site_config_updated_at ON public.site_config; "
                + "CREATE TRIGGER trg_site_config_updated_at " + event + " ON " + table
                + " FOR EACH ROW " + condition + " EXECUTE FUNCTION " + function + ";";
    }

    private String alternateFunctionSignature() {
        // PostgreSQL 不允许带 SQL 参数的 trigger 返回类型；用内置触发函数保留全部触发器名称。
        StringBuilder sql = new StringBuilder("""
                DROP FUNCTION public.nav_set_updated_at() CASCADE;
                CREATE FUNCTION public.nav_set_updated_at(value integer) RETURNS integer
                  LANGUAGE sql AS 'SELECT $1';
                """);
        for (String table : List.of("sys_user", "site_config", "nav_category", "nav_bookmark", "search_engine", "custom_link")) {
            sql.append("CREATE TRIGGER trg_").append(table).append("_updated_at BEFORE UPDATE ON public.")
                    .append(table).append(" FOR EACH ROW EXECUTE FUNCTION pg_catalog.suppress_redundant_updates_trigger();");
        }
        return sql.toString();
    }

    private void assertInvalidIndexRejectedAndRecovered(DatabaseSetupService installer,
                                                       Connection connection) throws Exception {
        String canonicalIndex = scalar(connection,
                "SELECT pg_catalog.pg_get_indexdef('public.idx_custom_link_position_sort'::regclass)");
        Map<String, String> before = dataAndSequenceSnapshot(connection);
        execute(connection, "DROP INDEX public.idx_custom_link_position_sort");
        try {
            // 两条权威种子链接均为 visible=true；真实并发唯一索引构建失败会留下 indisvalid=false。
            SQLException duplicate = assertThrows(SQLException.class, () -> execute(connection,
                    "CREATE UNIQUE INDEX CONCURRENTLY idx_custom_link_position_sort ON public.custom_link (visible)"));
            assertEquals("23505", duplicate.getSQLState());
            assertEquals("false", scalar(connection, """
                    SELECT indisvalid::text FROM pg_catalog.pg_index
                    WHERE indexrelid = 'public.idx_custom_link_position_sort'::regclass
                    """));
            assertDefinitionRejected(installer, connection, "invalid-index");
            assertDefinitionRejected(installer, connection, "invalid-index retry");
            assertEquals(before, dataAndSequenceSnapshot(connection));
        } finally {
            execute(connection, "DROP INDEX IF EXISTS public.idx_custom_link_position_sort");
            execute(connection, canonicalIndex);
        }
        assertReady(installer, connection);
        assertReady(installer, connection);
        assertEquals(before, dataAndSequenceSnapshot(connection));
        System.out.println("CATALOG_DEFINITION_RECOVERED=invalid-index");
    }

    private void assertInternalForeignKeyTriggerRejectedAndRecovered(DatabaseSetupService installer,
                                                                    Connection connection) throws Exception {
        String adminUsername = System.getenv("POSTGRESQL_CATALOG_TEST_ADMIN_USERNAME");
        String adminPassword = System.getenv("POSTGRESQL_CATALOG_TEST_ADMIN_PASSWORD");
        if (adminUsername == null && adminPassword == null) {
            System.out.println("CATALOG_INTERNAL_FK_TRIGGER=not-executed-without-disposable-admin-credentials");
            return;
        }
        assertNotNull(adminUsername);
        assertNotNull(adminPassword);
        String trigger = scalar(connection, """
                SELECT t.tgname FROM pg_catalog.pg_trigger t
                WHERE t.tgrelid = 'public.nav_bookmark'::regclass AND t.tgisinternal
                  AND t.tgconstraint = (SELECT oid FROM pg_catalog.pg_constraint
                    WHERE conrelid = 'public.nav_bookmark'::regclass AND conname = 'fk_nav_bookmark_category')
                ORDER BY t.tgname LIMIT 1
                """);
        String quotedTrigger = '"' + trigger.replace("\"", "\"\"") + '"';
        Map<String, String> before = dataAndSequenceSnapshot(connection);
        // 管理连接只能连接同一个已核对为空库来源的测试 URL，用于 PostgreSQL 要求超级用户的内部触发器 DDL。
        try (Connection admin = DriverManager.getConnection(System.getenv("POSTGRESQL_CATALOG_TEST_URL"),
                adminUsername, adminPassword)) {
            assertEquals(connection.getCatalog(), admin.getCatalog());
            assertEquals("true", scalar(admin, "SELECT rolsuper::text FROM pg_catalog.pg_roles WHERE rolname = current_user"));
            try {
                execute(admin, "ALTER TABLE public.nav_bookmark DISABLE TRIGGER " + quotedTrigger);
                assertDefinitionRejected(installer, connection, "internal-foreign-key-trigger");
                assertDefinitionRejected(installer, connection, "internal-foreign-key-trigger retry");
                assertEquals(before, dataAndSequenceSnapshot(connection));
            } finally {
                execute(admin, "ALTER TABLE public.nav_bookmark ENABLE TRIGGER " + quotedTrigger);
            }
        }
        assertReady(installer, connection);
        assertReady(installer, connection);
        assertEquals(before, dataAndSequenceSnapshot(connection));
        System.out.println("CATALOG_DEFINITION_RECOVERED=internal-foreign-key-trigger");
    }

    private void assertDefinitionRejected(DatabaseSetupService installer, Connection connection, String scenario) {
        InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                () -> inspect(installer, connection), scenario);
        BusinessException failure = assertInstanceOf(BusinessException.class, exception.getCause(), scenario);
        assertEquals(503, failure.getStatus().value(), scenario);
    }

    private Map<String, String> dataAndSequenceSnapshot(Connection connection) throws SQLException {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : List.of("schema_migration", "sys_user", "site_config", "portable_import_guard",
                "portable_import_operation", "nav_category", "nav_bookmark", "search_engine", "custom_link")) {
            snapshot.put("table:" + table, scalar(connection,
                    "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text FROM public." + table + " t"));
        }
        for (String sequence : List.of("sys_user_id_seq", "site_config_id_seq", "nav_category_id_seq",
                "nav_bookmark_id_seq", "search_engine_id_seq", "custom_link_id_seq")) {
            snapshot.put("sequence:" + sequence, scalar(connection,
                    "SELECT last_value || ':' || is_called FROM public." + sequence));
        }
        return snapshot;
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next(), sql);
            String value = result.getString(1);
            assertFalse(result.next(), sql);
            return value;
        }
    }

    private record DefinitionDrift(String name, String sql) { }

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
        return method.invoke(service, connection, true).toString();
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
