package com.example.nav.module.install.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 权威安装 schema 的列契约；只读核对目录，不能用同名列替代类型和默认值验证。 */
final class DatabaseSchemaColumnContract {
    private static final Map<String, List<Column>> TABLES = Map.of(
            "schema_migration", List.of(
                    varchar("filename", 255), column("checksum", "character(64)"), timestamp("applied_at")),
            "sys_user", List.of(
                    identity("id"), varchar("username", 50), varchar("password", 255),
                    varchar("nickname", 50), varchar("avatar", 255), varchar("role", 30, "admin"),
                    bool("status", true), integer("token_version"), timestamp("created_at"), timestamp("updated_at")),
            "site_config", List.of(
                    identity("id"), varchar("site_name", 50), varchar("site_description", 255),
                    varchar("publish_url", 255), varchar("background_type", 20, "color"),
                    varchar("background_color", 30, "#050505"), varchar("background_image", 500),
                    varchar("mobile_background_image", 500), varchar("font_color", 30, "#ffffff"),
                    bool("background_effect", false), bool("music_enabled", false), varchar("music_url", 500),
                    bool("subscribe_enabled", false), bool("top_content_enabled", true), varchar("message_text", 100),
                    integer("version"), column("install_completed_at", "timestamp without time zone"),
                    new Column("install_instance_id", "uuid", "gen_random_uuid()", ""),
                    timestamp("created_at"), timestamp("updated_at")),
            "portable_import_guard", List.of(column("id", "integer")),
            "portable_import_operation", List.of(
                    varchar("job_id", 64), varchar("preview_token", 64), column("user_id", "bigint"),
                    column("created_at", "timestamp without time zone"),
                    column("started_at", "timestamp without time zone"), timestamp("committed_at"),
                    column("site_version", "integer")),
            "nav_category", List.of(
                    identity("id"), varchar("name", 50), varchar("icon", 100), integer("sort_order"),
                    bool("visible", true), timestamp("created_at"), timestamp("updated_at")),
            "nav_bookmark", List.of(
                    identity("id"), column("category_id", "bigint"), varchar("name", 100), varchar("url", 500),
                    varchar("icon", 255), varchar("description", 255), integer("sort_order"),
                    bool("is_recommend", false), bool("is_external", true), bool("visible", true),
                    timestamp("created_at"), timestamp("updated_at")),
            "search_engine", List.of(
                    identity("id"), varchar("name", 50), varchar("icon", 255), varchar("search_url", 500),
                    varchar("placeholder", 100), bool("is_default", false), integer("sort_order"),
                    bool("visible", true), timestamp("created_at"), timestamp("updated_at")),
            "custom_link", List.of(
                    identity("id"), varchar("title", 50), varchar("url", 500), varchar("position", 20),
                    integer("sort_order"), bool("visible", true), timestamp("created_at"), timestamp("updated_at")));

    private DatabaseSchemaColumnContract() {}

    static boolean matches(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),
                       pg_catalog.pg_get_expr(d.adbin, d.adrelid), a.attidentity::text,
                       a.attgenerated::text,
                       CASE WHEN ty.typcollation = 0 THEN a.attcollation = 0
                            ELSE a.attcollation = 'pg_catalog."default"'::pg_catalog.regcollation END,
                       tn.nspname = 'pg_catalog' AND ty.typtype = 'b'
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_catalog.pg_type ty ON ty.oid = a.atttypid
                JOIN pg_catalog.pg_namespace tn ON tn.oid = ty.typnamespace
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = t.oid AND d.adnum = a.attnum
                WHERE n.nspname = 'public' AND t.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """)) {
            for (var table : TABLES.entrySet()) {
                statement.setString(1, table.getKey());
                try (var result = statement.executeQuery()) {
                    // 历史 ALTER ADD COLUMN 可能产生不同物理顺序，按稳定列名比较语义。
                    Map<String, Column> remaining = new HashMap<>();
                    table.getValue().forEach(column -> remaining.put(column.name(), column));
                    while (result.next()) {
                        Column expected = remaining.remove(result.getString(1));
                        if (expected == null || !expected.equals(new Column(result.getString(1), result.getString(2),
                                result.getString(3), result.getString(4)))
                                || !"".equals(result.getString(5))
                                || !result.getBoolean(6) || !result.getBoolean(7)) return false;
                    }
                    if (!remaining.isEmpty()) return false;
                }
            }
            return true;
        }
    }

    private static Column column(String name, String type) { return new Column(name, type, null, ""); }
    private static Column varchar(String name, int length) {
        return column(name, "character varying(" + length + ")");
    }
    private static Column varchar(String name, int length, String defaultValue) {
        return new Column(name, "character varying(" + length + ")",
                "'" + defaultValue + "'::character varying", "");
    }
    private static Column integer(String name) { return new Column(name, "integer", "0", ""); }
    private static Column bool(String name, boolean value) {
        return new Column(name, "boolean", Boolean.toString(value), "");
    }
    private static Column timestamp(String name) {
        return new Column(name, "timestamp without time zone", "CURRENT_TIMESTAMP", "");
    }
    private static Column identity(String name) { return new Column(name, "bigint", null, "d"); }
    private record Column(String name, String type, String defaultExpression, String identity) {}
}
