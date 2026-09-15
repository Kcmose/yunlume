package com.example.nav.module.install.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 核对对象的实际绑定和执行语义；对象同名不代表约束仍生效。 */
final class DatabaseSchemaObjectContract {
    private static final List<String> IDENTITY_TABLES = List.of(
            "sys_user", "site_config", "nav_category", "nav_bookmark", "search_engine", "custom_link");
    private static final String UPDATED_AT_BODY = """
            BEGIN
                IF NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
                    NEW.updated_at = CURRENT_TIMESTAMP;
                END IF;
                RETURN NEW;
            END;
            """.strip();

    private DatabaseSchemaObjectContract() {}

    static boolean matches(Connection connection) throws SQLException {
        return tablesMatch(connection) && constraintsMatch(connection) && indexesMatch(connection)
                && sequencesMatch(connection) && functionMatches(connection) && triggersMatch(connection);
    }

    private static boolean tablesMatch(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT count(*) = 0
                FROM pg_catalog.pg_class t
                JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relkind = 'r'
                  AND (t.relpersistence <> 'p' OR t.relrowsecurity OR t.relforcerowsecurity
                    OR t.relispartition
                    OR t.relam <> (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'heap')
                    OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid = t.oid)
                    OR EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class = t.oid)
                    OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i
                               WHERE i.inhrelid = t.oid OR i.inhparent = t.oid))
                """)) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static boolean constraintsMatch(Connection connection) throws SQLException {
        Map<String, Constraint> remaining = new HashMap<>();
        primary(remaining, "schema_migration", "filename");
        primary(remaining, "portable_import_guard", "id");
        primary(remaining, "portable_import_operation", "job_id");
        IDENTITY_TABLES.forEach(table -> primary(remaining, table, "id"));
        unique(remaining, "sys_user", "uk_sys_user_username", "username");
        unique(remaining, "portable_import_operation", "uk_portable_import_preview", "preview_token");
        check(remaining, "schema_migration", "chk_schema_migration_checksum", "checksum",
                "checksum ~ '^[0-9a-f]{64}$'::text");
        check(remaining, "site_config", "chk_site_config_background_type", "background_type",
                "background_type::text = ANY (ARRAY['color'::character varying, 'image'::character varying]::text[])");
        check(remaining, "site_config", "chk_site_config_version_range", "version", "version >= 0");
        check(remaining, "portable_import_guard", "chk_portable_import_guard_singleton", "id", "id = 1");
        check(remaining, "custom_link", "chk_custom_link_position", "position",
                "\"position\"::text = ANY (ARRAY['header'::character varying, 'footer'::character varying]::text[])");
        remaining.put("nav_bookmark.fk_nav_bookmark_category", new Constraint("f", List.of("category_id"),
                null, "nav_category", List.of("id"), "nav_category_pkey"));
        // conenforced/conperiod 从 PostgreSQL 18 起存在，不能在 14–17 的 SQL 中引用。
        String enforced = connection.getMetaData().getDatabaseMajorVersion() >= 18
                ? " AND c.conenforced AND NOT c.conperiod\n" : "";
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT t.relname || '.' || c.conname, c.contype::text,
                       ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num, ord)
                             JOIN pg_catalog.pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.num
                             ORDER BY k.ord),
                       pg_catalog.pg_get_expr(c.conbin, c.conrelid, true),
                       rt.relname,
                       ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY k(num, ord)
                             JOIN pg_catalog.pg_attribute a ON a.attrelid = rt.oid AND a.attnum = k.num
                             ORDER BY k.ord),
                       idx.relname,
                       c.convalidated AND NOT c.condeferrable AND NOT c.condeferred
                         AND c.conislocal AND c.coninhcount = 0 AND c.conparentid = 0 AND c.contypid = 0
                         AND c.connoinherit = (c.contype <> 'c')
                         AND (c.contype <> 'f' OR (rn.nspname = 'public'
                              AND c.confupdtype = 'c' AND c.confdeltype = 'c' AND c.confmatchtype = 's'))
                         AND (idx.oid IS NULL OR ixn.nspname = 'public')
                """ + enforced + """
                FROM pg_catalog.pg_constraint c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.connamespace
                LEFT JOIN pg_catalog.pg_class t ON t.oid = c.conrelid
                LEFT JOIN pg_catalog.pg_class rt ON rt.oid = c.confrelid
                LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rt.relnamespace
                LEFT JOIN pg_catalog.pg_class idx ON idx.oid = c.conindid
                LEFT JOIN pg_catalog.pg_namespace ixn ON ixn.oid = idx.relnamespace
                WHERE n.nspname = 'public' AND c.contype <> 'n'
                """)) {
            while (result.next()) {
                Constraint expected = remaining.remove(result.getString(1));
                Constraint actual = new Constraint(result.getString(2), strings(result, 3), result.getString(4),
                        result.getString(5), strings(result, 6), result.getString(7));
                if (expected == null || !expected.equals(actual) || !result.getBoolean(8)) return false;
            }
        }
        return remaining.isEmpty();
    }

    private static boolean indexesMatch(Connection connection) throws SQLException {
        Map<String, Index> remaining = new HashMap<>();
        primaryIndex(remaining, "schema_migration", "filename", "text_ops");
        primaryIndex(remaining, "portable_import_guard", "id", "int4_ops");
        primaryIndex(remaining, "portable_import_operation", "job_id", "text_ops");
        IDENTITY_TABLES.forEach(table -> primaryIndex(remaining, table, "id", "int8_ops"));
        index(remaining, "sys_user", "uk_sys_user_username", true, false, "uk_sys_user_username",
                List.of("username"), List.of("text_ops"), "0", null, null);
        index(remaining, "portable_import_operation", "uk_portable_import_preview", true, false,
                "uk_portable_import_preview", List.of("preview_token"), List.of("text_ops"), "0", null, null);
        index(remaining, "portable_import_operation", "idx_portable_import_user_committed", false, false, null,
                List.of("user_id", "committed_at"), List.of("int8_ops", "timestamp_ops"), "0 3", null, null);
        index(remaining, "nav_category", "idx_nav_category_sort", false, false, null,
                List.of("sort_order", "id"), List.of("int4_ops", "int8_ops"), "0 0", null, null);
        index(remaining, "nav_bookmark", "idx_nav_bookmark_category_sort", false, false, null,
                List.of("category_id", "sort_order", "id"), List.of("int8_ops", "int4_ops", "int8_ops"),
                "0 0 0", null, null);
        index(remaining, "search_engine", "uk_search_engine_one_visible_default", true, false, null,
                List.of(""), List.of("int4_ops"), "0", "1", "is_default IS TRUE AND visible IS TRUE");
        index(remaining, "search_engine", "idx_search_engine_visible_sort", false, false, null,
                List.of("visible", "sort_order", "id"), List.of("bool_ops", "int4_ops", "int8_ops"),
                "0 0 0", null, null);
        index(remaining, "custom_link", "idx_custom_link_position_sort", false, false, null,
                List.of("position", "sort_order", "id"), List.of("text_ops", "int4_ops", "int8_ops"),
                "0 0 0", null, null);
        // PostgreSQL 15 新增 NULLS NOT DISTINCT；权威定义使用默认的 NULLS DISTINCT。
        String nullsDistinct = connection.getMetaData().getDatabaseMajorVersion() >= 15
                ? " AND NOT i.indnullsnotdistinct\n" : "";
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT tbl.relname || '.' || idx.relname, i.indisunique, i.indisprimary, c.conname,
                       ARRAY(SELECT COALESCE(a.attname::text, '')
                             FROM unnest(i.indkey) WITH ORDINALITY k(num, ord)
                             LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = tbl.oid AND a.attnum = k.num
                             ORDER BY k.ord),
                       ARRAY(SELECT ns.nspname || '.' || op.opcname
                             FROM unnest(i.indclass) WITH ORDINALITY k(oid, ord)
                             JOIN pg_catalog.pg_opclass op ON op.oid = k.oid
                             JOIN pg_catalog.pg_namespace ns ON ns.oid = op.opcnamespace ORDER BY k.ord),
                       ARRAY(SELECT CASE WHEN k.oid = 0 THEN '' ELSE ns.nspname || '.' || col.collname END
                             FROM unnest(i.indcollation) WITH ORDINALITY k(oid, ord)
                             LEFT JOIN pg_catalog.pg_collation col ON col.oid = k.oid
                             LEFT JOIN pg_catalog.pg_namespace ns ON ns.oid = col.collnamespace ORDER BY k.ord),
                       i.indoption::text,
                       pg_catalog.pg_get_expr(i.indexprs, i.indrelid, true),
                       pg_catalog.pg_get_expr(i.indpred, i.indrelid, true),
                       am.amname = 'btree' AND idx.relkind = 'i' AND idx.relpersistence = 'p'
                         AND i.indisvalid AND i.indisready AND i.indislive AND i.indimmediate
                         AND NOT i.indisexclusion AND i.indnatts = i.indnkeyatts
                         AND tn.nspname = 'public'
                """ + nullsDistinct + """
                FROM pg_catalog.pg_index i
                JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = idx.relnamespace
                JOIN pg_catalog.pg_class tbl ON tbl.oid = i.indrelid
                JOIN pg_catalog.pg_namespace tn ON tn.oid = tbl.relnamespace
                JOIN pg_catalog.pg_am am ON am.oid = idx.relam
                LEFT JOIN pg_catalog.pg_constraint c
                  ON c.conrelid = i.indrelid AND c.conindid = i.indexrelid AND c.contype IN ('p', 'u', 'x')
                WHERE n.nspname = 'public'
                """)) {
            while (result.next()) {
                Index expected = remaining.remove(result.getString(1));
                Index actual = new Index(result.getBoolean(2), result.getBoolean(3), result.getString(4),
                        strings(result, 5), strings(result, 6), strings(result, 7), result.getString(8),
                        result.getString(9), result.getString(10));
                if (expected == null || !expected.equals(actual) || !result.getBoolean(11)) return false;
            }
        }
        return remaining.isEmpty();
    }

    private static boolean sequencesMatch(Connection connection) throws SQLException {
        Map<String, String> remaining = new HashMap<>();
        IDENTITY_TABLES.forEach(table -> remaining.put(table + "_id_seq", table));
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT seq.relname, tbl.relname,
                       s.seqtypid = 'pg_catalog.int8'::pg_catalog.regtype
                         AND s.seqstart = 1 AND s.seqincrement = 1 AND s.seqmin = 1
                         AND s.seqmax = 9223372036854775807 AND s.seqcache = 1 AND NOT s.seqcycle
                         AND seq.relpersistence = 'p' AND tn.nspname = 'public'
                         AND d.deptype = 'i' AND d.objsubid = 0
                         AND a.attname = 'id' AND a.attidentity = 'd' AND NOT a.attisdropped
                FROM pg_catalog.pg_class seq
                JOIN pg_catalog.pg_namespace n ON n.oid = seq.relnamespace
                LEFT JOIN pg_catalog.pg_sequence s ON s.seqrelid = seq.oid
                LEFT JOIN pg_catalog.pg_depend d
                  ON d.classid = 'pg_catalog.pg_class'::pg_catalog.regclass AND d.objid = seq.oid
                 AND d.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass AND d.deptype IN ('a', 'i')
                LEFT JOIN pg_catalog.pg_class tbl ON tbl.oid = d.refobjid
                LEFT JOIN pg_catalog.pg_namespace tn ON tn.oid = tbl.relnamespace
                LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = tbl.oid AND a.attnum = d.refobjsubid
                WHERE n.nspname = 'public' AND seq.relkind = 'S'
                """)) {
            while (result.next()) {
                String expected = remaining.remove(result.getString(1));
                if (expected == null || !expected.equals(result.getString(2)) || !result.getBoolean(3)) return false;
            }
        }
        return remaining.isEmpty();
    }

    private static boolean functionMatches(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT p.proname, p.prosrc,
                       l.lanname = 'plpgsql' AND p.prokind = 'f' AND p.prorettype = 'pg_catalog.trigger'::regtype
                         AND p.pronargs = 0 AND p.pronargdefaults = 0 AND p.provariadic = 0
                         AND NOT p.proretset AND NOT p.prosecdef AND NOT p.proleakproof AND NOT p.proisstrict
                         AND p.provolatile = 'v' AND p.proparallel = 'u'
                         AND p.proallargtypes IS NULL AND p.proargmodes IS NULL AND p.proargnames IS NULL
                         AND p.proargdefaults IS NULL AND p.proconfig IS NULL AND p.probin IS NULL
                         AND p.prosupport = 0 AND p.protrftypes IS NULL AND p.prosqlbody IS NULL
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_catalog.pg_language l ON l.oid = p.prolang
                WHERE n.nspname = 'public'
                """)) {
            if (!result.next() || !"nav_set_updated_at".equals(result.getString(1)) || !result.getBoolean(3)) return false;
            String body = result.getString(2);
            // 保留正文中的空白，只容许行尾及整体缩进差异，不把不同代码归一为相同字符串。
            if (body == null || !UPDATED_AT_BODY.equals(body.replace("\r\n", "\n").stripIndent().strip())) return false;
            return !result.next();
        }
    }

    private static boolean triggersMatch(Connection connection) throws SQLException {
        Map<String, Trigger> remaining = new HashMap<>();
        for (String table : IDENTITY_TABLES) {
            remaining.put(table + ".trg_" + table + "_updated_at",
                    new Trigger(false, "public.nav_set_updated_at", 19, null, null, null));
        }
        // 内部 RI 触发器的名字含动态 OID，按约束、表及内置函数绑定核对。
        foreignTrigger(remaining, "nav_category", "RI_FKey_cascade_del", 9, "nav_bookmark");
        foreignTrigger(remaining, "nav_category", "RI_FKey_cascade_upd", 17, "nav_bookmark");
        foreignTrigger(remaining, "nav_bookmark", "RI_FKey_check_ins", 5, "nav_category");
        foreignTrigger(remaining, "nav_bookmark", "RI_FKey_check_upd", 17, "nav_category");
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT tbl.relname, t.tgname, t.tgisinternal, pn.nspname || '.' || p.proname, t.tgtype,
                       rt.relname, idx.relname, c.conname,
                       t.tgenabled = 'O' AND t.tgparentid = 0
                         AND NOT t.tgdeferrable AND NOT t.tginitdeferred
                         AND t.tgnargs = 0 AND octet_length(t.tgargs) = 0 AND t.tgattr::text = ''
                         AND t.tgqual IS NULL AND t.tgoldtable IS NULL AND t.tgnewtable IS NULL
                         AND (rt.oid IS NULL OR rn.nspname = 'public')
                         AND (idx.oid IS NULL OR ixn.nspname = 'public')
                         AND (c.oid IS NULL OR (c.conrelid = 'public.nav_bookmark'::pg_catalog.regclass
                              AND c.contype = 'f' AND cn.nspname = 'public'))
                FROM pg_catalog.pg_trigger t
                JOIN pg_catalog.pg_class tbl ON tbl.oid = t.tgrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = tbl.relnamespace
                JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
                JOIN pg_catalog.pg_namespace pn ON pn.oid = p.pronamespace
                LEFT JOIN pg_catalog.pg_class rt ON rt.oid = t.tgconstrrelid
                LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rt.relnamespace
                LEFT JOIN pg_catalog.pg_class idx ON idx.oid = t.tgconstrindid
                LEFT JOIN pg_catalog.pg_namespace ixn ON ixn.oid = idx.relnamespace
                LEFT JOIN pg_catalog.pg_constraint c ON c.oid = t.tgconstraint
                LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid = c.connamespace
                WHERE n.nspname = 'public'
                """)) {
            while (result.next()) {
                boolean internal = result.getBoolean(3);
                String function = result.getString(4);
                String key = result.getString(1) + "." + (internal ? function : result.getString(2));
                Trigger expected = remaining.remove(key);
                Trigger actual = new Trigger(internal, function, result.getInt(5), result.getString(6),
                        result.getString(7), result.getString(8));
                if (expected == null || !expected.equals(actual) || !result.getBoolean(9)) return false;
            }
        }
        return remaining.isEmpty();
    }

    private static List<String> strings(ResultSet result, int column) throws SQLException {
        var array = result.getArray(column);
        if (array == null) return List.of();
        try { return Arrays.asList((String[]) array.getArray()); }
        finally { array.free(); }
    }

    private static void primary(Map<String, Constraint> map, String table, String column) {
        map.put(table + "." + table + "_pkey", new Constraint("p", List.of(column), null, null, List.of(), table + "_pkey"));
    }

    private static void unique(Map<String, Constraint> map, String table, String name, String column) {
        map.put(table + "." + name, new Constraint("u", List.of(column), null, null, List.of(), name));
    }

    private static void check(Map<String, Constraint> map, String table, String name, String column, String expression) {
        map.put(table + "." + name, new Constraint("c", List.of(column), expression, null, List.of(), null));
    }

    private static void primaryIndex(Map<String, Index> map, String table, String column, String opclass) {
        index(map, table, table + "_pkey", true, true, table + "_pkey", List.of(column), List.of(opclass), "0", null, null);
    }

    private static void index(Map<String, Index> map, String table, String name, boolean unique, boolean primary,
                              String constraint, List<String> keys, List<String> opclasses, String options,
                              String expression, String predicate) {
        map.put(table + "." + name, new Index(unique, primary, constraint, keys,
                opclasses.stream().map(value -> "pg_catalog." + value).toList(),
                opclasses.stream().map(value -> "text_ops".equals(value) ? "pg_catalog.default" : "").toList(),
                options, expression, predicate));
    }

    private static void foreignTrigger(Map<String, Trigger> map, String table, String function, int type, String referencedTable) {
        map.put(table + ".pg_catalog." + function,
                new Trigger(true, "pg_catalog." + function, type, referencedTable,
                        "nav_category_pkey", "fk_nav_bookmark_category"));
    }

    private record Constraint(String type, List<String> columns, String expression, String referencedTable,
                              List<String> referencedColumns, String index) {}
    private record Index(boolean unique, boolean primary, String constraint, List<String> keys,
                         List<String> opclasses, List<String> collations, String options, String expression,
                         String predicate) {}
    private record Trigger(boolean internal, String function, int type, String referencedTable,
                           String index, String constraint) {}
}
