package com.example.nav.module.install.service;

import com.example.nav.common.config.PostgresqlNotNullConstraints;

import com.example.nav.common.config.DatabaseInstallProperties;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.common.security.SecureTransportPolicy;
import com.example.nav.module.install.dto.DatabaseConfigureDTO;
import com.example.nav.module.install.dto.DatabaseConnectionDTO;
import com.example.nav.module.install.model.DatabaseConnectionSpec;
import com.example.nav.module.install.model.DatabaseSchemaState;
import com.example.nav.module.install.model.DatabaseSslMode;
import com.example.nav.module.install.vo.DatabaseConfigureVO;
import com.example.nav.module.install.vo.DatabaseTestVO;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Service
public class DatabaseSetupService {

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
    private static final Pattern DATABASE_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,63}$");
    private static final Pattern DNS_HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern IPV6_HOST = Pattern.compile("^[0-9A-Fa-f:.%]+$");
    private static final Pattern SAFE_USERNAME = Pattern.compile("^[^\\p{Cntrl}\\r\\n\\u0000]{1,128}$");
    private static final Pattern PEM_CERTIFICATE_CHAIN = Pattern.compile(
            "(?s)^(?:\\s*-----BEGIN CERTIFICATE-----[\\r\\n]+"
                    + "[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----\\s*)+$");
    private static final int MIN_POSTGRES_VERSION = 140000;
    private static final String INSTALL_SCHEMA = "schema-postgresql.sql";
    private static final String INSTANCE_MIGRATION = "20260815_0003_install_instance_identity.sql";
    private static final String INSTANCE_MIGRATION_CHECKSUM =
            "17df5851046d9a79eb24923b4760f8d0440b15a9a68d1609e2bffe2f1ce280fb";
    private static final List<String[]> REQUIRED_MIGRATIONS = List.of(
            new String[]{"20260812_0001_postgresql_baseline.sql",
                    "006e38274447656002de06d53f7d4154ba80984388dcb26d1223e636dfce91a6"},
            new String[]{"20260814_0002_web_install_state.sql",
                    "7347e9e96d3c2347e1067624b786437f3b509e9d7e7614e773c6b1e067596d86"},
            new String[]{INSTANCE_MIGRATION, INSTANCE_MIGRATION_CHECKSUM}
            ,new String[]{"20260904_0004_portable_import_operations.sql",
                    "4de5e2df8c8f6780f6d1b25e16ee1dd99b7335c7b7475afb83c63f78cfa7ac63"}
    );
    private static final long INSTALL_ADVISORY_LOCK = 0x58594e4156494741L;
    private static final AtomicBoolean CONFIGURATION_IN_PROGRESS = new AtomicBoolean();

    private final InstallAccessService accessService;
    private final DatabaseConfigurationStore configurationStore;
    private final DatabaseConnectionTicketStore ticketStore;
    private final DataSource currentDataSource;
    private final ConfigurableApplicationContext applicationContext;
    private final boolean autoRestart;
    private final boolean allowInsecureSetup;
    private final SecureTransportPolicy secureTransportPolicy;

    public DatabaseSetupService(
            InstallAccessService accessService,
            DatabaseConfigurationStore configurationStore,
            DatabaseConnectionTicketStore ticketStore,
            DataSource currentDataSource,
            ConfigurableApplicationContext applicationContext,
            DatabaseInstallProperties properties
    ) {
        this.accessService = accessService;
        this.configurationStore = configurationStore;
        this.ticketStore = ticketStore;
        this.currentDataSource = currentDataSource;
        this.applicationContext = applicationContext;
        this.autoRestart = properties.isAutoRestart();
        this.allowInsecureSetup = properties.isAllowInsecureSetup();
        this.secureTransportPolicy = new SecureTransportPolicy(
                properties.isTrustForwardedHttps(), properties.getTrustedProxyPeers());
    }

    public void requireSecureTransport(HttpServletRequest request) {
        requireSecureTransport(request, "数据库配置");
    }

    public void requireSecureTransport(HttpServletRequest request, String sensitiveResource) {
        if (allowInsecureSetup) return;
        secureTransportPolicy.requireSecure(request, sensitiveResource);
    }

    public DatabaseTestVO test(DatabaseConnectionDTO dto) {
        accessService.requireEnabled();
        requireUnconfiguredSource();
        if (CONFIGURATION_IN_PROGRESS.get()) {
            throw BusinessException.conflict("数据库配置任务正在执行");
        }
        DatabaseConnectionSpec spec = normalize(dto);
        Inspection inspection = inspect(spec);
        if (inspection.state() == DatabaseSchemaState.READY_INSTALLED) {
            throw BusinessException.conflict("目标数据库已经包含管理员或已完成安装，不能通过首次安装向导接入");
        }
        DatabaseConnectionTicketStore.IssuedTicket issued = ticketStore.issue(
                spec, inspection.state(), inspection.instanceId());
        return new DatabaseTestVO(
                true,
                issued.token(),
                issued.expiresAt(),
                inspection.state().name(),
                inspection.state() == DatabaseSchemaState.EMPTY
        );
    }

    public DatabaseConfigureVO configure(DatabaseConfigureDTO dto) {
        accessService.requireEnabled();
        requireUnconfiguredSource();
        if (dto == null) {
            throw BusinessException.badRequest("数据库配置参数不能为空");
        }
        if (!CONFIGURATION_IN_PROGRESS.compareAndSet(false, true)) {
            throw BusinessException.conflict("另一个数据库配置任务正在执行");
        }
        boolean pendingMarker = false;
        boolean remoteMutationStarted = false;
        try {
            configurationStore.verifyWritable();
            DatabaseConnectionTicketStore.Ticket ticket = ticketStore.consume(dto.connectionTicket());
            Inspection before = inspect(ticket.spec());
            requireUnchangedTarget(ticket, before);
            boolean initialized = false;
            if (before.state() == DatabaseSchemaState.EMPTY && !dto.initializeSchema()) {
                throw BusinessException.badRequest("空数据库必须确认初始化结构");
            }
            if (before.state() != DatabaseSchemaState.EMPTY && dto.initializeSchema()) {
                throw BusinessException.badRequest("目标数据库已有完整结构，不应重复初始化");
            }
            configurationStore.beginConfiguration();
            pendingMarker = true;

            Inspection ready;
            if (before.state() == DatabaseSchemaState.EMPTY) {
                // From this point a lost COMMIT acknowledgement is indistinguishable
                // from a rollback. Preserve PENDING on every failure.
                remoteMutationStarted = true;
                ready = initializeEmptyDatabase(ticket.spec());
                initialized = true;
            } else {
                ready = inspect(ticket.spec());
                requireUnchangedTarget(ticket, ready);
            }

            if (ready.state() != DatabaseSchemaState.READY_UNINSTALLED || ready.instanceId() == null) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "目标数据库初始化后状态不符合要求");
            }
            Path runtimeCa = ticket.spec().caCertificatePem() == null
                    ? null : configurationStore.caCertificateFile();
            String runtimeUrl = jdbcUrl(ticket.spec(), runtimeCa);
            configurationStore.saveExternal(ticket.spec(), runtimeUrl, ready.instanceId());
            configurationStore.markConfigured(ready.instanceId());
            pendingMarker = false;
            ticketStore.advanceGeneration();
            boolean restartRequired = true;
            if (autoRestart) {
                scheduleContainerRestart();
            }
            return new DatabaseConfigureVO(true, initialized, false, restartRequired);
        } catch (RuntimeException exception) {
            if (pendingMarker && !remoteMutationStarted && !configurationStore.hasPersistedConnection()) {
                try {
                    configurationStore.clearPendingConfiguration();
                } catch (RuntimeException ignored) {
                    // An uncleared marker intentionally leaves a fail-closed state.
                }
            }
            throw exception;
        } finally {
            CONFIGURATION_IN_PROGRESS.set(false);
        }
    }

    private void requireUnconfiguredSource() {
        if (configurationStore.hasCompletedMarker()) {
            throw BusinessException.conflict("站点已经完成安装，不能更改数据库连接");
        }
        if (!configurationStore.isUnconfiguredSource()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "数据库连接已由运行配置管理，断线时不能通过安装向导改库");
        }
        try (Connection connection = currentDataSource.getConnection()) {
            if (hasPositiveInstallationMarker(connection)) {
                try {
                    String instanceId = queryString(connection,
                            "SELECT install_instance_id::text FROM public.site_config LIMIT 1");
                    if (instanceId != null) configurationStore.markCompleted(instanceId);
                } catch (RuntimeException | SQLException ignored) {
                    // A positive database-side completion fact remains authoritative
                    // even when the local marker cannot be reconstructed.
                }
                throw BusinessException.conflict(
                        "站点已经完成安装，不能更改数据库连接");
            }
            Inspection current = inspectConnection(connection, false);
            if (current.state() == DatabaseSchemaState.READY_INSTALLED) {
                if (current.instanceId() != null) {
                    try {
                        configurationStore.markCompleted(current.instanceId());
                    } catch (RuntimeException ignored) {
                        // The database marker is authoritative; local lock persistence
                        // failure must never reopen database selection.
                    }
                }
                throw BusinessException.conflict("站点已经完成安装，不能更改数据库连接");
            }
        } catch (BusinessException exception) {
            if (exception.getStatus() == HttpStatus.CONFLICT) {
                throw exception;
            }
            // An UNCONFIGURED source may intentionally point at a placeholder
            // datasource. Only a positively identified completed site blocks setup.
        } catch (SQLException | RuntimeException ignored) {
            // UNCONFIGURED is the only source allowed to continue when the current
            // datasource cannot be reached. No connection detail is logged.
        }
    }

    private boolean hasPositiveInstallationMarker(Connection connection) {
        try {
            if (queryBoolean(connection,
                    "SELECT pg_catalog.to_regclass('public.sys_user') IS NOT NULL")
                    && queryLong(connection, "SELECT COUNT(*) FROM public.sys_user") > 0) {
                return true;
            }
            return queryBoolean(connection,
                    "SELECT pg_catalog.to_regclass('public.site_config') IS NOT NULL")
                    && queryLong(connection, "SELECT COUNT(*) FROM public.site_config "
                    + "WHERE install_completed_at IS NOT NULL") > 0;
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }

    private DatabaseConnectionSpec normalize(DatabaseConnectionDTO dto) {
        if (dto == null) {
            throw BusinessException.badRequest("数据库连接参数不能为空");
        }

        String host = validateHost(dto.host());
        List<String> resolvedAddresses = resolveSafeExternalAddresses(host);
        int port = validatePort(dto.port() == null ? 5432 : dto.port());
        String database = validateDatabase(dto.database());
        String username = validateUsername(dto.username());
        String password = validatePassword(dto.password());
        DatabaseSslMode sslMode = dto.sslMode() == null ? DatabaseSslMode.VERIFY_FULL : dto.sslMode();
        if (sslMode == DatabaseSslMode.DISABLE || sslMode == DatabaseSslMode.PREFER) {
            throw BusinessException.badRequest("外部数据库不允许关闭或降级 TLS 验证");
        }
        String caCertificate = null;
        if (sslMode == DatabaseSslMode.REQUIRE) {
            if (!Boolean.TRUE.equals(dto.acknowledgeUnverifiedTls())) {
                throw BusinessException.badRequest("REQUIRE 模式必须确认其不校验证书和主机名的风险");
            }
            if (dto.caCertificatePem() != null && !dto.caCertificatePem().isBlank()) {
                throw BusinessException.badRequest("REQUIRE 模式不应提交 CA 证书");
            }
        } else {
            caCertificate = validateCaCertificate(dto.caCertificatePem());
        }
        return new DatabaseConnectionSpec(
                host,
                port,
                database,
                username,
                password,
                sslMode,
                caCertificate,
                resolvedAddresses
        );
    }

    private Inspection inspect(DatabaseConnectionSpec spec) {
        requireResolutionUnchanged(spec);
        Path temporaryCa = null;
        try {
            if (spec.caCertificatePem() != null) {
                temporaryCa = createTemporaryCa(spec.caCertificatePem());
            }
            try (HikariDataSource candidate = candidateDataSource(spec, temporaryCa);
                 Connection connection = candidate.getConnection()) {
                return inspectConnection(connection, true);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (SQLException | IOException | RuntimeException exception) {
            throw unavailableConnection();
        } finally {
            deleteTemporaryCa(temporaryCa);
        }
    }

    private Inspection inspectConnection(Connection connection, boolean external) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO public");
        }
        String product = connection.getMetaData().getDatabaseProductName();
        if (!"PostgreSQL".equalsIgnoreCase(product)) {
            throw BusinessException.badRequest("安装向导只支持 PostgreSQL 数据库");
        }
        int version = queryInt(connection, "SHOW server_version_num");
        if (version < MIN_POSTGRES_VERSION) {
            throw BusinessException.badRequest("外部数据库版本必须为 PostgreSQL 14 或更高版本");
        }
        if (external && queryBoolean(connection,
                "SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user")) {
            throw BusinessException.badRequest("外部数据库必须使用非超级用户的专用角色");
        }
        if (!queryBoolean(connection, "SELECT has_schema_privilege(current_user, 'public', 'USAGE') "
                + "AND has_schema_privilege(current_user, 'public', 'CREATE')")) {
            throw BusinessException.badRequest("数据库角色必须拥有 public schema 的 USAGE 和 CREATE 权限");
        }
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
        Set<String> tables = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                tables.add(result.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        if (tables.isEmpty()) {
            long schemaObjects = queryLong(connection, """
                    SELECT
                        (SELECT COUNT(*) FROM pg_catalog.pg_class c
                         JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public')
                      + (SELECT COUNT(*) FROM pg_catalog.pg_proc p
                         JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                         WHERE n.nspname = 'public')
                      + (SELECT COUNT(*) FROM pg_catalog.pg_type t
                         JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                         WHERE n.nspname = 'public')
                    """);
            if (schemaObjects != 0) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        "public schema 不是完全空的，不能自动初始化");
            }
            return new Inspection(DatabaseSchemaState.EMPTY, null);
        }
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
                sequences.add(result.getString(1).toLowerCase(Locale.ROOT));
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
        CORE_SEQUENCES.forEach(name -> expectedRelations.add("s:" + name));
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
                || !triggers.equals(CORE_TRIGGERS)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库结构、索引、约束或触发器与当前版本不完全一致");
        }
        long objectsNotControlledByRole = queryLong(connection, """
                SELECT
                    (SELECT COUNT(*)
                     FROM pg_catalog.pg_class c
                     JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'S', 'i')
                       AND NOT pg_catalog.pg_has_role(current_user, c.relowner, 'USAGE'))
                  + (SELECT COUNT(*)
                     FROM pg_catalog.pg_proc p
                     JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                     WHERE n.nspname = 'public'
                       AND NOT pg_catalog.pg_has_role(current_user, p.proowner, 'USAGE'))
                """);
        if (objectsNotControlledByRole != 0) {
            throw BusinessException.badRequest(
                    "数据库角色必须拥有或属于全部导航结构对象的所有者角色");
        }
        validateReadySchema(connection);
        long siteCount = queryLong(connection, "SELECT COUNT(*) FROM site_config");
        long userCount = queryLong(connection, "SELECT COUNT(*) FROM sys_user");
        long completedCount = queryLong(connection,
                "SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL");
        if (siteCount != 1) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "站点配置必须且只能有一条");
        }
        String instanceId = queryString(connection, "SELECT install_instance_id::text FROM site_config LIMIT 1");
        DatabaseSchemaState state = userCount > 0 || completedCount > 0
                ? DatabaseSchemaState.READY_INSTALLED
                : DatabaseSchemaState.READY_UNINSTALLED;
        return new Inspection(state, instanceId);
    }

    private void validateReadySchema(Connection connection) throws SQLException {
        List<String> probes = List.of(
                "SELECT filename, checksum, applied_at FROM schema_migration WHERE 1 = 0",
                "SELECT id, username, password, role, token_version FROM sys_user WHERE 1 = 0",
                "SELECT id, install_completed_at, install_instance_id, version FROM site_config WHERE 1 = 0",
                "SELECT id FROM portable_import_guard WHERE 1 = 0",
                "SELECT job_id, preview_token, user_id, committed_at, site_version "
                        + "FROM portable_import_operation WHERE 1 = 0",
                "SELECT id, name, sort_order, visible FROM nav_category WHERE 1 = 0",
                "SELECT id, category_id, name, url, sort_order, visible FROM nav_bookmark WHERE 1 = 0",
                "SELECT id, name, search_url, is_default, visible FROM search_engine WHERE 1 = 0",
                "SELECT id, title, url, position, visible FROM custom_link WHERE 1 = 0"
        );
        for (String probe : probes) {
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery(probe).close();
            }
        }
        if (queryLong(connection, "SELECT COUNT(*) FROM public.schema_migration")
                != REQUIRED_MIGRATIONS.size()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库迁移记录与当前版本不完全一致");
        }
        for (String[] migration : REQUIRED_MIGRATIONS) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM public.schema_migration WHERE filename = ? AND checksum = ?
                    """)) {
                statement.setString(1, migration[0]);
                statement.setString(2, migration[1]);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                                "目标数据库迁移记录与当前版本不完全一致");
                    }
                }
            }
        }
        long guardCount = queryLong(connection,
                "SELECT COUNT(*) FROM public.portable_import_guard");
        long validGuardCount = queryLong(connection,
                "SELECT COUNT(*) FROM public.portable_import_guard WHERE id = 1");
        if (guardCount != 1 || validGuardCount != 1) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "目标数据库数据导入单例锁记录损坏");
        }
    }

    private void requireUnchangedTarget(DatabaseConnectionTicketStore.Ticket ticket, Inspection current) {
        if (current.state() == DatabaseSchemaState.READY_INSTALLED) {
            throw BusinessException.conflict("目标数据库已在连接测试后被安装");
        }
        if (current.state() != ticket.schemaState()) {
            throw BusinessException.conflict("目标数据库在连接测试后发生变化，请重新测试");
        }
        if (ticket.expectedInstanceId() != null
                && !ticket.expectedInstanceId().equals(current.instanceId())) {
            throw BusinessException.conflict("目标数据库实例身份已变化，请重新测试");
        }
    }

    private Inspection initializeEmptyDatabase(DatabaseConnectionSpec spec) {
        requireResolutionUnchanged(spec);
        Path temporaryCa = null;
        try {
            if (spec.caCertificatePem() != null) {
                temporaryCa = createTemporaryCa(spec.caCertificatePem());
            }
            String script = strictInitializationScript(new ClassPathResource(INSTALL_SCHEMA)
                    .getContentAsString(StandardCharsets.UTF_8));
            try (HikariDataSource candidate = candidateDataSource(spec, temporaryCa);
                 Connection connection = candidate.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL search_path TO public");
                    statement.execute("SELECT pg_catalog.pg_advisory_xact_lock("
                            + INSTALL_ADVISORY_LOCK + ")");
                    Inspection underLock = inspectConnection(connection, true);
                    if (underLock.state() != DatabaseSchemaState.EMPTY) {
                        throw BusinessException.conflict(
                                "只允许初始化完全空的 PostgreSQL schema");
                    }
                    statement.execute(script);
                    Inspection ready = inspectConnection(connection, true);
                    if (ready.state() != DatabaseSchemaState.READY_UNINSTALLED
                            || ready.instanceId() == null) {
                        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                                "数据库结构初始化事务校验失败");
                    }
                    connection.commit();
                    return ready;
                } catch (SQLException | RuntimeException exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                        // The caller receives the generic initialization failure.
                    }
                    throw exception;
                }
            }
        } catch (SQLException | IOException | RuntimeException exception) {
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "数据库结构初始化失败；已执行的事务不会提交");
        } finally {
            deleteTemporaryCa(temporaryCa);
        }
    }

    private HikariDataSource candidateDataSource(DatabaseConnectionSpec spec, Path caPath) {
        requireResolutionUnchanged(spec);
        HikariConfig config = new HikariConfig();
        config.setPoolName("nav-install-candidate");
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(jdbcUrl(spec, caPath));
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(Duration.ofSeconds(6).toMillis());
        config.setValidationTimeout(Duration.ofSeconds(3).toMillis());
        config.setInitializationFailTimeout(-1);
        config.setConnectionInitSql("SET search_path TO public");
        return new HikariDataSource(config);
    }

    private String jdbcUrl(DatabaseConnectionSpec spec, Path caPath) {
        String host = spec.host().contains(":") ? "[" + spec.host() + "]" : spec.host();
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(spec.port()).append('/')
                .append(spec.database())
                .append("?sslmode=").append(spec.sslMode().jdbcValue())
                .append("&currentSchema=public")
                .append("&connectTimeout=5&socketTimeout=10&tcpKeepAlive=true")
                .append("&ApplicationName=yunlume-installer");
        if (caPath != null) {
            url.append("&sslrootcert=").append(URLEncoder.encode(
                    caPath.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private Path createTemporaryCa(String pem) throws IOException {
        Path path = Files.createTempFile("nav-install-ca-", ".pem");
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Test hosts may not expose POSIX permissions; the file is still
            // created with a random owner-scoped name and immediately removed.
        }
        Files.writeString(path, pem, StandardCharsets.US_ASCII);
        return path;
    }

    private void deleteTemporaryCa(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Never log certificate-adjacent temporary paths.
        }
    }

    private String validateCaCertificate(String value) {
        if (value == null || value.isBlank() || value.length() > 65536) {
            throw BusinessException.badRequest("VERIFY_CA/VERIFY_FULL 必须提供不超过 64KiB 的 CA 证书");
        }
        if (value.contains("PRIVATE KEY")
                || value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\r' && codePoint != '\n' && codePoint != '\t')
                || !PEM_CERTIFICATE_CHAIN.matcher(value).matches()) {
            throw BusinessException.badRequest("CA 证书格式无效");
        }
        try (InputStream input = new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII))) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException("empty certificate chain");
            }
        } catch (Exception exception) {
            throw BusinessException.badRequest("CA 证书格式无效");
        }
        return value.endsWith("\n") ? value : value + "\n";
    }

    private String withoutTransactionWrapper(String script) {
        String withoutBegin = script.replaceFirst("(?is)^\\s*BEGIN\\s*;", "");
        return withoutBegin.replaceFirst("(?is)COMMIT\\s*;\\s*$", "");
    }

    private String strictInitializationScript(String script) {
        return withoutTransactionWrapper(script)
                .replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE")
                .replace("CREATE UNIQUE INDEX IF NOT EXISTS", "CREATE UNIQUE INDEX")
                .replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX");
    }

    private String validateHost(String value) {
        if (value == null) throw BusinessException.badRequest("数据库主机不能为空");
        String host = value.trim();
        boolean dns = DNS_HOST.matcher(host).matches()
                && !host.startsWith(".") && !host.endsWith(".")
                && !host.startsWith("-") && !host.endsWith("-")
                && !host.contains("..");
        boolean ipv6 = IPV6_HOST.matcher(host).matches() && host.contains(":");
        if (!dns && !ipv6) {
            throw BusinessException.badRequest("数据库主机格式无效");
        }
        return host;
    }

    private int validatePort(int value) {
        if (value < 1 || value > 65535) {
            throw BusinessException.badRequest("数据库端口必须在 1-65535 之间");
        }
        return value;
    }

    private List<String> resolveSafeExternalAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new UnknownHostException("empty resolution");
            }
            for (InetAddress address : addresses) {
                String numericAddress = address.getHostAddress().toLowerCase(Locale.ROOT);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isMulticastAddress()
                        || numericAddress.equals("100.100.100.200")
                        || numericAddress.equals("169.254.169.254")
                        || numericAddress.equals("fd00:ec2:0:0:0:0:0:254")) {
                    throw BusinessException.badRequest(
                            "外部数据库主机不能指向本机、链路本地、元数据或组播地址");
                }
            }
            return java.util.Arrays.stream(addresses)
                    .map(address -> address.getHostAddress().toLowerCase(Locale.ROOT))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (BusinessException exception) {
            throw exception;
        } catch (UnknownHostException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "外部数据库主机无法安全解析");
        }
    }

    private void requireResolutionUnchanged(DatabaseConnectionSpec spec) {
        List<String> current = resolveSafeExternalAddresses(spec.host());
        if (!current.equals(spec.resolvedAddresses())) {
            throw BusinessException.conflict(
                    "外部数据库主机解析结果已变化，请重新测试连接");
        }
    }

    private String validateDatabase(String value) {
        String database = value == null ? "" : value.trim();
        if (!DATABASE_NAME.matcher(database).matches()) {
            throw BusinessException.badRequest("数据库名称只能包含英文字母、数字、点、下划线和连字符");
        }
        return database;
    }

    private String validateUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!SAFE_USERNAME.matcher(username).matches()) {
            throw BusinessException.badRequest("数据库用户名格式无效");
        }
        return username;
    }

    private String validatePassword(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024
                || value.codePoints().anyMatch(codePoint -> codePoint == 0
                || codePoint == '\r' || codePoint == '\n')) {
            throw BusinessException.badRequest("数据库密码格式无效");
        }
        return value;
    }

    private int queryInt(Connection connection, String sql) throws SQLException {
        return Integer.parseInt(queryString(connection, sql));
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("missing result");
            return result.getLong(1);
        }
    }

    private boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("missing result");
            return result.getBoolean(1);
        }
    }

    private Set<String> queryNames(Connection connection, String sql) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                names.add(result.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("missing result");
            return result.getString(1);
        }
    }

    private BusinessException unavailableConnection() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "无法连接目标 PostgreSQL；请检查地址、账号、TLS 与防火墙配置");
    }

    private void scheduleContainerRestart() {
        Thread restart = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            if (applicationContext.isActive()) {
                System.exit(0);
            }
        }, "database-config-restart");
        restart.setDaemon(false);
        restart.start();
    }

    private record Inspection(DatabaseSchemaState state, String instanceId) {
    }
}
