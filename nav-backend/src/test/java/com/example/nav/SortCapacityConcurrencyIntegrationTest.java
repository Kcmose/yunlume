package com.example.nav;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.category.service.CategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨越 SQL 查询分段的完整排序仍由一个事务提交；两个连接真实等锁后再验证提交和恢复。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sort_capacity_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class SortCapacityConcurrencyIntegrationTest extends ConcurrentDatabaseTestSupport {
    private static final int ITEM_COUNT = 1001;
    private static final long FIRST_ID = 6_800_000_000L;
    private static final long LAST_ID = FIRST_ID + ITEM_COUNT - 1;
    private static final long OWNER_ID = FIRST_ID - 1;

    @Autowired CategoryService categories;
    @Autowired BookmarkService bookmarks;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    enum Kind {
        CATEGORY("nav_category"), BOOKMARK("nav_bookmark");
        final String table;
        Kind(String table) { this.table = table; }
    }

    static Stream<Arguments> commitOutcomes() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(false, true)
                .map(rollbackFirst -> Arguments.of(kind, rollbackFirst)));
    }

    @AfterEach
    void cleanCreatedRows() {
        jdbc.update("DELETE FROM nav_bookmark WHERE id BETWEEN ? AND ?", FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM nav_category WHERE id BETWEEN ? AND ?", OWNER_ID, LAST_ID);
    }

    @ParameterizedTest
    @MethodSource("commitOutcomes")
    void oppositeLargeSortsRemainCompleteWhenTheFirstCommitsOrRollsBack(Kind kind, boolean rollbackFirst)
            throws Exception {
        seed(kind);
        List<Map<String, Object>> originalFields = unchangedFields(kind);
        List<Integer> originalSort = storedSort(kind);
        int originalVersion = version();
        List<SortItemDTO> reverse = payload(true);
        List<SortItemDTO> forward = payload(false);

        overlap(kind, reverse, forward, rollbackFirst, originalSort, originalVersion);

        assertThat(storedSort(kind)).containsExactlyElementsOf(expectedSort(false));
        assertThat(unchangedFields(kind)).isEqualTo(originalFields);
        assertThat(version()).isEqualTo(originalVersion + (rollbackFirst ? 1 : 2));
        if (rollbackFirst) {
            // 同一份失败请求可以整体重试；末段记录和缓存版本一起提交。
            assertThat(sort(kind, reverse)).containsExactlyElementsOf(reverse.stream().map(SortItemDTO::id).toList());
            assertThat(storedSort(kind)).containsExactlyElementsOf(expectedSort(true));
            assertThat(unchangedFields(kind)).isEqualTo(originalFields);
            assertThat(version()).isEqualTo(originalVersion + 2);
        }
    }

    private void seed(Kind kind) {
        if (kind == Kind.BOOKMARK) {
            jdbc.update("INSERT INTO nav_category (id, name, sort_order, visible) VALUES (?, ?, 0, TRUE)",
                    OWNER_ID, "并发排序所属分类");
        }
        List<Integer> indexes = IntStream.range(0, ITEM_COUNT).boxed().toList();
        String insert = kind == Kind.CATEGORY
                ? "INSERT INTO nav_category (id, name, sort_order, visible) VALUES (?, ?, ?, ?)"
                : "INSERT INTO nav_bookmark (id, name, sort_order, visible, category_id, url) VALUES (?, ?, ?, ?, ?, ?)";
        jdbc.batchUpdate(insert, indexes, 1000, (statement, index) -> {
            statement.setLong(1, FIRST_ID + index);
            statement.setString(2, "并发排序项目 " + index);
            statement.setInt(3, index * 10 + 3);
            statement.setBoolean(4, index % 2 == 0);
            if (kind == Kind.BOOKMARK) {
                statement.setLong(5, OWNER_ID);
                statement.setString(6, "https://example.com/sort/" + index);
            }
        });
    }

    private List<SortItemDTO> payload(boolean reverse) {
        return IntStream.range(0, ITEM_COUNT)
                .mapToObj(index -> new SortItemDTO(FIRST_ID + (reverse ? ITEM_COUNT - index - 1 : index), index * 10))
                .toList();
    }

    private List<Integer> expectedSort(boolean reverse) {
        return IntStream.range(0, ITEM_COUNT)
                .map(index -> (reverse ? ITEM_COUNT - index - 1 : index) * 10).boxed().toList();
    }

    private List<Long> sort(Kind kind, List<SortItemDTO> items) {
        Stream<Long> ids = switch (kind) {
            case CATEGORY -> categories.sort(items).stream().map(item -> item.id());
            case BOOKMARK -> bookmarks.sort(items).stream().map(item -> item.id());
        };
        return ids.filter(id -> id >= FIRST_ID && id <= LAST_ID).toList();
    }

    private List<Integer> storedSort(Kind kind) {
        return jdbc.queryForList("SELECT sort_order FROM " + kind.table + " WHERE id BETWEEN ? AND ? ORDER BY id",
                Integer.class, FIRST_ID, LAST_ID);
    }

    private List<Map<String, Object>> unchangedFields(Kind kind) {
        return jdbc.queryForList("SELECT * FROM " + kind.table + " WHERE id BETWEEN ? AND ? ORDER BY id", FIRST_ID, LAST_ID)
                .stream().map(row -> {
                    Map<String, Object> stable = new LinkedHashMap<>(row);
                    stable.remove("sort_order");
                    stable.remove("updated_at");
                    return stable;
                }).toList();
    }

    private int version() { return jdbc.queryForObject("SELECT version FROM site_config WHERE id=1", Integer.class); }

    private void overlap(Kind kind, List<SortItemDTO> firstItems, List<SortItemDTO> secondItems,
                         boolean rollbackFirst, List<Integer> originalSort, int originalVersion) throws Exception {
        var applied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var secondSession = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertThat(sort(kind, firstItems)).containsExactlyElementsOf(firstItems.stream().map(SortItemDTO::id).toList());
                applied.countDown();
                await(release);
                if (rollbackFirst) tx.setRollbackOnly();
            }));
            assertThat(applied.await(30, TimeUnit.SECONDS)).as("首事务已完成 1001 项写入但尚未提交").isTrue();
            var second = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                secondSession.set(databaseSessionId());
                started.countDown();
                assertThat(sort(kind, secondItems)).containsExactlyElementsOf(secondItems.stream().map(SortItemDTO::id).toList());
            }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            awaitBlockedSession(secondSession.get());
            // 独立连接只看到请求前的完整结果，不能看到首事务已写入的任意一段。
            assertThat(storedSort(kind)).containsExactlyElementsOf(originalSort);
            assertThat(version()).isEqualTo(originalVersion);
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("并发排序等待超时");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发排序测试被中断", error);
        }
    }
}
