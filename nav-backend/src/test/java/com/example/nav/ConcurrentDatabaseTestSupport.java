package com.example.nav;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认沿用各测试的 H2；真实 PostgreSQL 只允许外部创建的专用一次性测试库。 */
abstract class ConcurrentDatabaseTestSupport {

    private static final Set<String> ALLOWED_DATABASES = Set.of(
            "yunlume_concurrency_test_pg14", "yunlume_concurrency_test_pg18");
    private static final String POSTGRESQL_URL = System.getenv("CONCURRENT_MUTATION_TEST_URL");

    @Autowired private JdbcTemplate concurrencyJdbc;
    @Autowired private DataSource concurrencyDatasource;

    /** 必须在受测事务内获取，确保标记的是随后执行业务 SQL 的同一个连接。 */
    protected int databaseSessionId() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        return concurrencyJdbc.execute((ConnectionCallback<Integer>) connection -> {
            String query = switch (connection.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> "SELECT pg_backend_pid()";
                case "H2" -> "SELECT SESSION_ID()";
                default -> throw new IllegalStateException("并发回归仅支持 H2 和 PostgreSQL");
            };
            try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
                if (!result.next()) throw new IllegalStateException("无法读取受测事务的数据库连接 ID");
                return result.getInt(1);
            }
        });
    }

    /** 使用独立观察连接确认数据库已实际阻塞第二个事务，再允许首事务提交或回滚。 */
    protected void awaitBlockedSession(int sessionId) throws SQLException, InterruptedException {
        assertThat(sessionId).isPositive();
        try (Connection observer = concurrencyDatasource.getConnection()) {
            observer.setAutoCommit(true);
            String product = observer.getMetaData().getDatabaseProductName();
            String query = switch (product) {
                case "PostgreSQL" -> "SELECT cardinality(pg_blocking_pids(?))";
                case "H2" -> """
                        SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS
                        WHERE SESSION_ID = ? AND BLOCKER_ID IS NOT NULL
                        """;
                default -> throw new IllegalStateException("并发回归仅支持 H2 和 PostgreSQL");
            };
            try (var statement = observer.prepareStatement(query)) {
                statement.setInt(1, sessionId);
                statement.setQueryTimeout(2);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    try (var result = statement.executeQuery()) {
                        if (result.next() && result.getInt(1) > 0) {
                            System.out.printf("已确认数据库等锁：%s session=%d%n", product, sessionId);
                            return;
                        }
                    }
                    Thread.sleep(10);
                }
            }
            throw new AssertionError("10 秒内未观察到数据库事务等锁：" + product + " session=" + sessionId);
        }
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        if (POSTGRESQL_URL == null || POSTGRESQL_URL.isBlank()) return;
        if (!POSTGRESQL_URL.matches("jdbc:postgresql://[^/?#]+/yunlume_concurrency_test_pg(?:14|18)")) {
            throw new IllegalStateException("并发回归仅允许命名明确的 PostgreSQL 一次性测试库");
        }
        registry.add("spring.datasource.url", () -> POSTGRESQL_URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> requiredEnvironment("CONCURRENT_MUTATION_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("CONCURRENT_MUTATION_TEST_PASSWORD"));
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @BeforeAll
    static void verifyDisposableDatabase(@Autowired DataSource datasource) throws Exception {
        if (POSTGRESQL_URL == null || POSTGRESQL_URL.isBlank()) return;
        try (Connection connection = datasource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getCatalog()).isIn(ALLOWED_DATABASES);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("真实 PostgreSQL 并发回归缺少环境变量 " + name);
        }
        return value;
    }
}
