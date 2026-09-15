package com.example.nav.module.install.service;

import com.example.nav.common.config.PostgresqlNotNullConstraints;
import com.example.nav.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** 安装和继续安装共用的只读结构合同；不修改目标数据库，也不执行目标用户函数。 */
final class DatabaseSchemaContract {
    private static final Set<String> CORE_TABLES = Set.of(
            "schema_migration",
            "sys_user",
            "site_config",
            "portable_import_guard",
            "portable_import_operation",
            "nav_category",
            "nav_bookmark",
            "search_engine",
            "custom_link"
    );
    private static final Set<String> CORE_SEQUENCES = Set.of(
            "sys_user_id_seq",
            "site_config_id_seq",
            "nav_category_id_seq",
            "nav_bookmark_id_seq",
            "search_engine_id_seq",
            "custom_link_id_seq"
    );
    private static final Set<String> CORE_INDEXES = Set.of(
            "schema_migration_pkey", "sys_user_pkey", "uk_sys_user_username",
            "site_config_pkey", "nav_category_pkey", "idx_nav_category_sort",
            "portable_import_guard_pkey", "portable_import_operation_pkey",
            "uk_portable_import_preview", "idx_portable_import_user_committed",
            "nav_bookmark_pkey", "idx_nav_bookmark_category_sort",
            "search_engine_pkey", "uk_search_engine_one_visible_default",
            "idx_search_engine_visible_sort", "custom_link_pkey",
            "idx_custom_link_position_sort"
    );
    private static final Set<String> CORE_CONSTRAINTS = Set.of(
            "schema_migration_pkey", "chk_schema_migration_checksum",
            "sys_user_pkey", "uk_sys_user_username",
            "site_config_pkey", "chk_site_config_background_type", "chk_site_config_version_range",
            "portable_import_guard_pkey", "chk_portable_import_guard_singleton",
            "portable_import_operation_pkey", "uk_portable_import_preview",
            "nav_category_pkey", "nav_bookmark_pkey", "fk_nav_bookmark_category",
            "search_engine_pkey", "custom_link_pkey", "chk_custom_link_position"
    );
    private static final Set<String> CORE_TRIGGERS = Set.of(
            "trg_sys_user_updated_at", "trg_site_config_updated_at",
            "trg_nav_category_updated_at", "trg_nav_bookmark_updated_at",
            "trg_search_engine_updated_at", "trg_custom_link_updated_at"
    );
    private static final Set<String> CORE_COLUMNS = Set.of(
            "schema_migration.filename", "schema_migration.checksum", "schema_migration.applied_at",
            "sys_user.id", "sys_user.username", "sys_user.password", "sys_user.nickname",
            "sys_user.avatar", "sys_user.role", "sys_user.status", "sys_user.token_version",
            "sys_user.created_at", "sys_user.updated_at",
            "site_config.id", "site_config.site_name", "site_config.site_description",
            "site_config.publish_url", "site_config.background_type", "site_config.background_color",
            "site_config.background_image", "site_config.mobile_background_image",
            "site_config.font_color", "site_config.background_effect", "site_config.music_enabled",
            "site_config.music_url", "site_config.subscribe_enabled", "site_config.top_content_enabled",
            "site_config.message_text", "site_config.version", "site_config.install_completed_at",
            "site_config.install_instance_id", "site_config.created_at", "site_config.updated_at",
            "portable_import_guard.id", "portable_import_operation.job_id",
            "portable_import_operation.preview_token", "portable_import_operation.user_id",
            "portable_import_operation.created_at", "portable_import_operation.started_at",
            "portable_import_operation.committed_at", "portable_import_operation.site_version",
            "nav_category.id", "nav_category.name", "nav_category.icon", "nav_category.sort_order",
            "nav_category.visible", "nav_category.created_at", "nav_category.updated_at",
            "nav_bookmark.id", "nav_bookmark.category_id", "nav_bookmark.name", "nav_bookmark.url",
            "nav_bookmark.icon", "nav_bookmark.description", "nav_bookmark.sort_order",
            "nav_bookmark.is_recommend", "nav_bookmark.is_external", "nav_bookmark.visible",
            "nav_bookmark.created_at", "nav_bookmark.updated_at",
            "search_engine.id", "search_engine.name", "search_engine.icon", "search_engine.search_url",
            "search_engine.placeholder", "search_engine.is_default", "search_engine.sort_order",
            "search_engine.visible", "search_engine.created_at", "search_engine.updated_at",
            "custom_link.id", "custom_link.title", "custom_link.url", "custom_link.position",
            "custom_link.sort_order", "custom_link.visible", "custom_link.created_at",
            "custom_link.updated_at"
    );
    private DatabaseSchemaContract() {}

    static void requireCompatible(Connection connection) throws SQLException {
        long extraSchemas = queryLong(connection, """
                SELECT COUNT(*) FROM pg_catalog.pg_namespace
                WHERE nspname <> 'public'
                  AND nspname <> 'information_schema'
                  AND nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                """);
        if (extraSchemas != 0) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库包含额外用户 schema；请使用预创建的专用空数据库");
        }
        Set<String> tables = queryNames(connection, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """);
        if (!tables.equals(CORE_TABLES)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库包含缺失或未知数据表，不允许安装向导接管");
        }
        long unknownViews = queryLong(connection, """
                SELECT COUNT(*)
                FROM information_schema.views
                WHERE table_schema = 'public'
                """);
        if (unknownViews != 0) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库包含未知视图，不允许安装向导接管");
        }
        long publicFunctions = queryLong(connection, """
                SELECT COUNT(*)
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public'
                """);
        long expectedFunctions = queryLong(connection, """
                SELECT COUNT(*)
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'nav_set_updated_at'
                """);
        long unknownTypes = queryLong(connection, """
                SELECT COUNT(*)
                FROM pg_catalog.pg_type t
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname = 'public'
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_catalog.pg_class c
                      WHERE c.reltype = t.oid AND c.relnamespace = n.oid AND c.relkind = 'r'
                  )
                  AND NOT (
                      t.typcategory = 'A' AND EXISTS (
                          SELECT 1
                          FROM pg_catalog.pg_type element
                          JOIN pg_catalog.pg_class c ON c.reltype = element.oid
                          WHERE t.typelem = element.oid
                            AND c.relnamespace = n.oid AND c.relkind = 'r'
                      )
                  )
                """);
        if (publicFunctions != 1 || expectedFunctions != 1 || unknownTypes != 0) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库包含未知函数或类型，不允许安装向导接管");
        }
        Set<String> sequences = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.relname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'S'
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                sequences.add(result.getString(1));
            }
        }
        if (!sequences.equals(CORE_SEQUENCES)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库包含缺失或未知序列，不允许安装向导接管");
        }
        Set<String> columns = queryNames(connection, """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                """);
        Set<String> indexes = queryNames(connection, """
                SELECT indexname FROM pg_catalog.pg_indexes WHERE schemaname = 'public'
                """);
        Set<String> relations = queryNames(connection, """
                SELECT c.relkind::text || ':' || c.relname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                """);
        Set<String> expectedRelations = new HashSet<>();
        CORE_TABLES.forEach(name -> expectedRelations.add("r:" + name));
        CORE_SEQUENCES.forEach(name -> expectedRelations.add("S:" + name));
        CORE_INDEXES.forEach(name -> expectedRelations.add("i:" + name));
        Set<String> constraints = queryNames(connection, """
                SELECT c.conname
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = 'public' AND c.contype <> 'n'
                """);
        Set<String> triggers = queryNames(connection, """
                SELECT t.tgname
                FROM pg_catalog.pg_trigger t
                JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND NOT t.tgisinternal
                """);
        if (!columns.equals(CORE_COLUMNS)
                || !indexes.equals(CORE_INDEXES)
                || !relations.equals(expectedRelations)
                || !constraints.equals(CORE_CONSTRAINTS)
                || !PostgresqlNotNullConstraints.matches(connection, CORE_TABLES)
                || !DatabaseSchemaColumnContract.matches(connection)
                || !triggers.equals(CORE_TRIGGERS)
                || !DatabaseSchemaObjectContract.matches(connection)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库结构、索引、约束或触发器与当前版本不完全一致");
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("missing result");
            return result.getLong(1);
        }
    }

    private static Set<String> queryNames(Connection connection, String sql) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

}
