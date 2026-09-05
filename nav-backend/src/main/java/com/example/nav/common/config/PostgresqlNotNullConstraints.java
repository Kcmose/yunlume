package com.example.nav.common.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 按权威 schema 验证非空列，并兼容 PostgreSQL 18 新增的 NOT NULL 目录记录。 */
public final class PostgresqlNotNullConstraints {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "schema_migration", Set.of("filename", "checksum", "applied_at"),
            "sys_user", Set.of("id", "username", "password", "role", "status", "token_version",
                    "created_at", "updated_at"),
            "site_config", Set.of("id", "site_name", "background_type", "background_color",
                    "font_color", "background_effect", "music_enabled", "subscribe_enabled",
                    "top_content_enabled", "version", "install_instance_id", "created_at", "updated_at"),
            "portable_import_guard", Set.of("id"),
            "portable_import_operation", Set.of("job_id", "preview_token", "user_id", "created_at",
                    "started_at", "committed_at", "site_version"),
            "nav_category", Set.of("id", "name", "sort_order", "visible", "created_at", "updated_at"),
            "nav_bookmark", Set.of("id", "category_id", "name", "url", "sort_order", "is_recommend",
                    "is_external", "visible", "created_at", "updated_at"),
            "search_engine", Set.of("id", "name", "search_url", "is_default", "sort_order", "visible",
                    "created_at", "updated_at"),
            "custom_link", Set.of("id", "title", "url", "position", "sort_order", "visible",
                    "created_at", "updated_at"));

    private PostgresqlNotNullConstraints() {}

    public static boolean matches(Connection connection, Set<String> tables) throws SQLException {
        boolean catalogNotNull = connection.getMetaData().getDatabaseMajorVersion() >= 18;
        for (String table : tables) {
            Set<String> expected = REQUIRED_COLUMNS.get(table);
            if (expected == null) throw new IllegalArgumentException("Unknown schema table: " + table);
            Set<String> attributes = new HashSet<>();
            try (var statement = connection.prepareStatement("""
                    SELECT a.attname FROM pg_catalog.pg_attribute a
                    JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                    WHERE n.nspname = 'public' AND t.relname = ?
                      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
                    """)) {
                statement.setString(1, table);
                try (var result = statement.executeQuery()) {
                    while (result.next()) attributes.add(result.getString(1));
                }
            }
            if (!attributes.equals(expected)) return false;
            if (!catalogNotNull) continue;

            // 14–17 没有 conenforced 字段；只有 18+ 执行新增目录契约检查。
            Set<String> constraints = new HashSet<>();
            try (var statement = connection.prepareStatement("""
                    SELECT c.contype, a.attname,
                           c.conenforced AND c.convalidated AND NOT c.condeferrable
                           AND NOT c.condeferred AND c.conislocal AND c.coninhcount = 0
                           AND NOT c.connoinherit AND c.conparentid = 0
                           AND c.contypid = 0 AND c.conindid = 0 AND c.confrelid = 0
                           AND c.conbin IS NULL AND cardinality(c.conkey) = 1
                           AND a.attnotnull AND NOT a.attisdropped AS valid_not_null,
                           c.conenforced
                    FROM pg_catalog.pg_constraint c
                    JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                    LEFT JOIN pg_catalog.pg_attribute a
                      ON a.attrelid = t.oid AND a.attnum = c.conkey[1] AND a.attnum > 0
                    WHERE n.nspname = 'public' AND t.relname = ?
                    """)) {
                statement.setString(1, table);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        if (!result.getBoolean(4)) return false;
                        if ("n".equals(result.getString(1))
                                && (!result.getBoolean(3) || !constraints.add(result.getString(2)))) {
                            return false;
                        }
                    }
                }
            }
            if (!constraints.equals(expected)) return false;
        }
        return true;
    }
}
