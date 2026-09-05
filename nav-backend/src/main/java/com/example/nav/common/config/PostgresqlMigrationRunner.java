package com.example.nav.common.config;

import com.example.nav.module.install.service.DatabaseConfigurationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DatabaseMetaData;
import java.util.HexFormat;
import java.util.List;

/** Applies checksum-pinned PostgreSQL migrations through the application's configured datasource. */
@Slf4j
@Component
public class PostgresqlMigrationRunner implements InitializingBean {

    static final String MIGRATION_FILENAME = "20260904_0004_portable_import_operations.sql";
    static final String MIGRATION_RESOURCE = "database/migrations/" + MIGRATION_FILENAME;
    static final String MIGRATION_CHECKSUM =
            "4de5e2df8c8f6780f6d1b25e16ee1dd99b7335c7b7475afb83c63f78cfa7ac63";
    private static final long ADVISORY_LOCK_ID = 6366211110262552385L;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final DatabaseConfigurationStore configurationStore;

    public PostgresqlMigrationRunner(
            DataSource dataSource,
            DatabaseConfigurationStore configurationStore
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.dataSource = dataSource;
        this.configurationStore = configurationStore;
    }

    @Override
    public void afterPropertiesSet() {
        if (configurationStore.isUnconfiguredSource()) {
            log.info("PostgreSQL migration startup skipped for fresh unconfigured installation");
            return;
        }

        ClassPathResource migration = verifiedMigrationResource();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            if (!isPostgresql()) {
                log.debug("PostgreSQL migration startup is not applicable to this datasource");
                return;
            }
            jdbc.queryForObject("SELECT pg_catalog.pg_advisory_xact_lock(?)", Object.class, ADVISORY_LOCK_ID);
            requireMigrationRegistry();
            List<String> registrations = jdbc.queryForList(
                    "SELECT checksum FROM public.schema_migration WHERE filename = ?",
                    String.class, MIGRATION_FILENAME);
            if (registrations.size() > 1) {
                throw corrupt("duplicate migration registration");
            }
            if (registrations.size() == 1) {
                if (!MIGRATION_CHECKSUM.equals(registrations.get(0))) {
                    throw corrupt("migration checksum registration mismatch");
                }
                requireAppliedSchema();
                log.info("PostgreSQL migration {} already applied", MIGRATION_FILENAME);
                return;
            }

            jdbc.execute((ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection, migration);
                return null;
            });
            requireAppliedSchema();
            int inserted = jdbc.update(
                    "INSERT INTO public.schema_migration (filename, checksum) VALUES (?, ?)",
                    MIGRATION_FILENAME, MIGRATION_CHECKSUM);
            if (inserted != 1) {
                throw corrupt("migration registration was not inserted exactly once");
            }
            log.info("PostgreSQL migration {} applied", MIGRATION_FILENAME);
        });
    }

    private boolean isPostgresql() {
        return jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            return "PostgreSQL".equals(metadata.getDatabaseProductName());
        });
    }

    private void requireMigrationRegistry() {
        Boolean exact = jdbc.queryForObject("""
                SELECT pg_catalog.to_regclass('public.schema_migration') IS NOT NULL
                  AND (SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'schema_migration') = 3
                  AND (SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'schema_migration'
                          AND is_nullable = 'NO'
                          -- Canonical unqualified DDL reports NULL here even though
                          -- collatable attributes use pg_catalog."default" internally.
                          AND collation_name IS NULL
                          AND is_identity = 'NO' AND identity_generation IS NULL
                          AND is_generated = 'NEVER' AND generation_expression IS NULL
                          AND (
                            (column_name = 'filename' AND ordinal_position = 1
                              AND data_type = 'character varying'
                              AND character_maximum_length = 255 AND column_default IS NULL)
                            OR (column_name = 'checksum' AND ordinal_position = 2
                              AND data_type = 'character'
                              AND character_maximum_length = 64 AND column_default IS NULL)
                            OR (column_name = 'applied_at' AND ordinal_position = 3
                              AND data_type = 'timestamp without time zone'
                              AND upper(column_default) = 'CURRENT_TIMESTAMP'))) = 3
                  AND (SELECT count(*)
                         FROM pg_catalog.pg_attribute a
                        WHERE a.attrelid = 'public.schema_migration'::regclass
                          AND a.attnum > 0 AND NOT a.attisdropped
                          AND ((a.attnum = 1 AND a.attname = 'filename'
                                AND a.attcollation = 'pg_catalog."default"'::pg_catalog.regcollation)
                            OR (a.attnum = 2 AND a.attname = 'checksum'
                                AND a.attcollation = 'pg_catalog."default"'::pg_catalog.regcollation)
                            OR (a.attnum = 3 AND a.attname = 'applied_at'
                                AND a.attcollation = 0::oid))) = 3
                  AND (SELECT count(*) FROM pg_catalog.pg_constraint
                        WHERE conrelid = 'public.schema_migration'::regclass) = 2
                  AND (SELECT count(*) FROM pg_catalog.pg_constraint
                        WHERE conrelid = 'public.schema_migration'::regclass
                          AND convalidated AND NOT condeferrable AND NOT condeferred
                          AND conislocal AND coninhcount = 0 AND (
                          (conname = 'schema_migration_pkey' AND contype = 'p'
                            AND connoinherit AND conkey::text = '{1}')
                          OR (conname = 'chk_schema_migration_checksum' AND contype = 'c'
                            AND NOT connoinherit
                            AND pg_catalog.pg_get_expr(conbin, conrelid, true)
                              = 'checksum ~ ''^[0-9a-f]{64}$''::text'))) = 2
                  AND (SELECT count(*) FROM pg_catalog.pg_index
                        WHERE indrelid = 'public.schema_migration'::regclass) = 1
                  AND (SELECT count(*)
                         FROM pg_catalog.pg_index i
                         JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                         JOIN pg_catalog.pg_am am ON am.oid = idx.relam
                         JOIN pg_catalog.pg_constraint c
                           ON c.conrelid = i.indrelid AND c.conindid = i.indexrelid
                         JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS key(attnum, ordinal_position)
                           ON key.ordinal_position <= i.indnkeyatts
                         JOIN pg_catalog.pg_attribute att
                           ON att.attrelid = i.indrelid AND att.attnum = key.attnum
                        WHERE i.indrelid = 'public.schema_migration'::regclass
                          AND idx.relname = 'schema_migration_pkey'
                          AND idx.relkind = 'i' AND am.amname = 'btree'
                          AND i.indisunique AND i.indisprimary
                          AND i.indisvalid AND i.indisready AND i.indislive
                          AND i.indpred IS NULL AND i.indexprs IS NULL
                          AND i.indnkeyatts = 1 AND i.indnatts = 1
                          AND c.conname = 'schema_migration_pkey'
                          AND key.ordinal_position = 1 AND att.attname = 'filename'
                          AND NOT pg_catalog.pg_index_column_has_property(
                              i.indexrelid, 1, 'desc')
                          AND NOT pg_catalog.pg_index_column_has_property(
                              i.indexrelid, 1, 'nulls_first')) = 1
                """, Boolean.class);
        if (!Boolean.TRUE.equals(exact)) {
            throw corrupt("schema_migration is partial or corrupt");
        }
    }

    private void requireAppliedSchema() {
        Boolean siteVersionRange = jdbc.queryForObject("""
                SELECT count(*) = 1
                  FROM pg_catalog.pg_constraint
                 WHERE conrelid = 'public.site_config'::regclass
                   AND conname = 'chk_site_config_version_range'
                   AND contype = 'c' AND convalidated
                   AND NOT condeferrable AND NOT condeferred
                   AND conislocal AND coninhcount = 0 AND NOT connoinherit
                   AND pg_catalog.pg_get_expr(conbin, conrelid, true) = 'version >= 0'
                """, Boolean.class);
        Integer guardColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'portable_import_guard'
                   AND column_name = 'id' AND ordinal_position = 1
                   AND data_type = 'integer' AND is_nullable = 'NO'
                   AND collation_name IS NULL
                   AND column_default IS NULL
                   AND is_identity = 'NO' AND identity_generation IS NULL
                   AND is_generated = 'NEVER' AND generation_expression IS NULL
                """, Integer.class);
        Integer totalGuardColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'portable_import_guard'
                """, Integer.class);
        Integer operationColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'portable_import_operation'
                   AND is_nullable = 'NO'
                   AND collation_name IS NULL
                   AND is_identity = 'NO' AND identity_generation IS NULL
                   AND is_generated = 'NEVER' AND generation_expression IS NULL
                   AND (
                     (column_name = 'job_id' AND ordinal_position = 1
                          AND data_type = 'character varying'
                          AND character_maximum_length = 64 AND column_default IS NULL)
                     OR (column_name = 'preview_token' AND ordinal_position = 2
                          AND data_type = 'character varying'
                          AND character_maximum_length = 64 AND column_default IS NULL)
                     OR (column_name = 'user_id' AND ordinal_position = 3
                          AND data_type = 'bigint' AND column_default IS NULL)
                     OR (column_name = 'created_at' AND ordinal_position = 4
                          AND data_type = 'timestamp without time zone' AND column_default IS NULL)
                     OR (column_name = 'started_at' AND ordinal_position = 5
                          AND data_type = 'timestamp without time zone' AND column_default IS NULL)
                     OR (column_name = 'committed_at' AND ordinal_position = 6
                          AND data_type = 'timestamp without time zone'
                          AND upper(column_default) = 'CURRENT_TIMESTAMP')
                     OR (column_name = 'site_version' AND ordinal_position = 7
                          AND data_type = 'integer' AND column_default IS NULL)
                   )
                """, Integer.class);
        Integer totalOperationColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'portable_import_operation'
                """, Integer.class);
        Integer exactAppliedCollations = jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_catalog.pg_attribute a
                 WHERE a.attnum > 0 AND NOT a.attisdropped
                   AND ((a.attrelid = 'public.portable_import_guard'::regclass
                         AND a.attnum = 1 AND a.attname = 'id' AND a.attcollation = 0::oid)
                     OR (a.attrelid = 'public.portable_import_operation'::regclass AND (
                       (a.attnum = 1 AND a.attname = 'job_id'
                         AND a.attcollation = 'pg_catalog."default"'::pg_catalog.regcollation)
                       OR (a.attnum = 2 AND a.attname = 'preview_token'
                         AND a.attcollation = 'pg_catalog."default"'::pg_catalog.regcollation)
                       OR (a.attnum = 3 AND a.attname = 'user_id' AND a.attcollation = 0::oid)
                       OR (a.attnum = 4 AND a.attname = 'created_at' AND a.attcollation = 0::oid)
                       OR (a.attnum = 5 AND a.attname = 'started_at' AND a.attcollation = 0::oid)
                       OR (a.attnum = 6 AND a.attname = 'committed_at' AND a.attcollation = 0::oid)
                       OR (a.attnum = 7 AND a.attname = 'site_version' AND a.attcollation = 0::oid))))
                """, Integer.class);
        Integer expectedConstraints = jdbc.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_constraint
                 WHERE convalidated AND NOT condeferrable AND NOT condeferred
                   AND conislocal AND coninhcount = 0 AND (
                   (conrelid = 'public.portable_import_guard'::regclass AND (
                     (conname = 'portable_import_guard_pkey' AND contype = 'p'
                       AND connoinherit AND conkey::text = '{1}')
                     OR (conname = 'chk_portable_import_guard_singleton' AND contype = 'c'
                       AND NOT connoinherit
                       AND pg_catalog.pg_get_expr(conbin, conrelid, true) = 'id = 1')))
                   OR (conrelid = 'public.portable_import_operation'::regclass AND (
                     (conname = 'portable_import_operation_pkey' AND contype = 'p'
                       AND connoinherit AND conkey::text = '{1}')
                     OR (conname = 'uk_portable_import_preview' AND contype = 'u'
                       AND connoinherit AND conkey::text = '{2}'))))
                """, Integer.class);
        Integer totalConstraints = jdbc.queryForObject("""
                SELECT count(*) FROM pg_catalog.pg_constraint
                 WHERE conrelid IN ('public.portable_import_guard'::regclass,
                                    'public.portable_import_operation'::regclass)
                """, Integer.class);
        Boolean exactIndexes = jdbc.queryForObject("""
                WITH expected(table_name, index_name, is_unique, is_primary, constraint_name,
                              key_names, is_descending, nulls_first) AS (
                  VALUES
                    ('portable_import_guard', 'portable_import_guard_pkey', true, true,
                     'portable_import_guard_pkey', ARRAY['id']::text[],
                     ARRAY[false]::boolean[], ARRAY[false]::boolean[]),
                    ('portable_import_operation', 'portable_import_operation_pkey', true, true,
                     'portable_import_operation_pkey', ARRAY['job_id']::text[],
                     ARRAY[false]::boolean[], ARRAY[false]::boolean[]),
                    ('portable_import_operation', 'uk_portable_import_preview', true, false,
                     'uk_portable_import_preview', ARRAY['preview_token']::text[],
                     ARRAY[false]::boolean[], ARRAY[false]::boolean[]),
                    ('portable_import_operation', 'idx_portable_import_user_committed', false, false,
                     NULL, ARRAY['user_id', 'committed_at']::text[],
                     ARRAY[false, true]::boolean[], ARRAY[false, true]::boolean[])
                ), actual AS (
                  SELECT tbl.relname AS table_name, idx.relname AS index_name,
                         i.indisunique AS is_unique, i.indisprimary AS is_primary,
                         c.conname AS constraint_name, am.amname AS access_method,
                         idx.relkind AS index_kind, i.indisvalid, i.indisready, i.indislive,
                         i.indpred IS NULL AS is_non_partial,
                         i.indexprs IS NULL AS has_no_expressions,
                         i.indnkeyatts, i.indnatts,
                         array_agg(att.attname::text ORDER BY key.ordinality) AS key_names,
                         array_agg(pg_catalog.pg_index_column_has_property(
                             i.indexrelid, key.ordinality::integer, 'desc')
                             ORDER BY key.ordinality) AS is_descending,
                         array_agg(pg_catalog.pg_index_column_has_property(
                             i.indexrelid, key.ordinality::integer, 'nulls_first')
                             ORDER BY key.ordinality) AS nulls_first
                    FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_class idx ON idx.oid = i.indexrelid
                    JOIN pg_catalog.pg_class tbl ON tbl.oid = i.indrelid
                    JOIN pg_catalog.pg_namespace ns ON ns.oid = tbl.relnamespace
                    JOIN pg_catalog.pg_am am ON am.oid = idx.relam
                    JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS key(attnum, ordinality)
                      ON key.ordinality <= i.indnkeyatts
                    LEFT JOIN pg_catalog.pg_attribute att
                      ON att.attrelid = tbl.oid AND att.attnum = key.attnum
                    LEFT JOIN pg_catalog.pg_constraint c
                      ON c.conrelid = i.indrelid AND c.conindid = i.indexrelid
                   WHERE ns.nspname = 'public'
                     AND tbl.relname IN ('portable_import_guard', 'portable_import_operation')
                   GROUP BY tbl.relname, idx.relname, i.indisunique, i.indisprimary,
                            c.conname, am.amname, idx.relkind, i.indisvalid, i.indisready,
                            i.indislive, i.indpred, i.indexprs, i.indnkeyatts, i.indnatts,
                            i.indexrelid
                )
                SELECT (SELECT count(*) FROM pg_catalog.pg_index
                         WHERE indrelid = 'public.portable_import_guard'::regclass) = 1
                   AND (SELECT count(*) FROM pg_catalog.pg_index
                         WHERE indrelid = 'public.portable_import_operation'::regclass) = 3
                   AND (SELECT count(*) FROM actual) = 4
                   AND count(*) = 4
                  FROM expected e
                  JOIN actual a
                    ON a.table_name = e.table_name AND a.index_name = e.index_name
                   AND a.is_unique = e.is_unique AND a.is_primary = e.is_primary
                   AND a.constraint_name IS NOT DISTINCT FROM e.constraint_name
                   AND a.access_method = 'btree' AND a.index_kind = 'i'
                   AND a.indisvalid AND a.indisready AND a.indislive
                   AND a.is_non_partial AND a.has_no_expressions
                   AND a.indnkeyatts = cardinality(e.key_names)
                   AND a.indnatts = cardinality(e.key_names)
                   AND a.key_names = e.key_names
                   AND a.is_descending = e.is_descending
                   AND a.nulls_first = e.nulls_first
                """, Boolean.class);
        Integer singleton = jdbc.queryForObject(
                "SELECT count(*) FROM public.portable_import_guard WHERE id = 1", Integer.class);
        Integer allGuardRows = jdbc.queryForObject(
                "SELECT count(*) FROM public.portable_import_guard", Integer.class);
        if (!Boolean.TRUE.equals(siteVersionRange)
                || !Integer.valueOf(1).equals(guardColumns)
                || !Integer.valueOf(1).equals(totalGuardColumns)
                || !Integer.valueOf(7).equals(operationColumns)
                || !Integer.valueOf(7).equals(totalOperationColumns)
                || !Integer.valueOf(8).equals(exactAppliedCollations)
                || !Integer.valueOf(4).equals(expectedConstraints)
                || !Integer.valueOf(4).equals(totalConstraints)
                || !Boolean.TRUE.equals(exactIndexes)
                || !Integer.valueOf(1).equals(singleton)
                || !Integer.valueOf(1).equals(allGuardRows)) {
            throw corrupt("registered migration schema is partial or corrupt");
        }
    }

    private ClassPathResource verifiedMigrationResource() {
        ClassPathResource resource = new ClassPathResource(MIGRATION_RESOURCE);
        try (var input = resource.getInputStream()) {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            if (!MIGRATION_CHECKSUM.equals(actual)) {
                throw corrupt("classpath migration checksum mismatch");
            }
            return resource;
        } catch (IOException exception) {
            throw new IllegalStateException("Required PostgreSQL migration resource is missing", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private IllegalStateException corrupt(String reason) {
        return new IllegalStateException("PostgreSQL migration " + MIGRATION_FILENAME + " rejected: " + reason);
    }
}
