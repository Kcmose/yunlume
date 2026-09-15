package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 大列表经过真实 HTTP 参数校验、Service 和数据库事务，同组用例可在专用 PostgreSQL 库复跑。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:sort_capacity_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SortCapacityIntegrationTest extends ConcurrentDatabaseTestSupport {

    private static final long FIRST_ID = 8_800_000_000L;
    private static final long BOOKMARK_CATEGORY_ID = FIRST_ID + 20_000;
    private static final long UNKNOWN_ID = FIRST_ID + 30_000;
    private static final String FAILURE_CONSTRAINT = "sort_capacity_last_write_failure";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserMapper users;
    @Autowired private JwtTokenService tokens;

    private Kind seededKind;
    private int seededCount;
    private boolean bookmarkCategoryCreated;
    private boolean failureConstraintCreated;

    enum Kind {
        CATEGORY("nav_category", "categories"),
        BOOKMARK("nav_bookmark", "bookmarks");

        final String table;
        final String path;

        Kind(String table, String path) {
            this.table = table;
            this.path = path;
        }
    }

    enum InvalidTail { DUPLICATE_ID, UNKNOWN_ID, NEGATIVE_SORT_ORDER }

    record SortValue(long id, int sortOrder) { }

    static Stream<Arguments> capacities() {
        return Stream.of(Kind.values()).flatMap(kind -> IntStream.of(1000, 1001, 2001, 10001)
                .mapToObj(count -> Arguments.of(kind, count)));
    }

    static Stream<Arguments> invalidTails() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(InvalidTail.values())
                .map(invalid -> Arguments.of(kind, invalid)));
    }

    @ParameterizedTest(name = "{0} 完整保存 {1} 项排序")
    @MethodSource("capacities")
    void completeLargeOrderIsSavedInOneRequest(Kind kind, int count) throws Exception {
        seed(kind, count);
        int versionBefore = version();
        long started = System.nanoTime();

        String response = sort(kind, reversedOrder(count))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertCompleteOrder(kind, count, response);
        assertThat(version()).isEqualTo(versionBefore + 1);
        System.out.printf("大列表排序验收：%s %d 项，接口及结果核验耗时 %d ms%n",
                kind, count, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    @ParameterizedTest(name = "{0} 第 1001 项 {1} 时整批不变")
    @MethodSource("invalidTails")
    void invalidItemBeyondFirstBatchCannotPartiallySave(Kind kind, InvalidTail invalid) throws Exception {
        int count = 1001;
        seed(kind, count);
        List<Map<String, Object>> before = storedRows(kind, count);
        int versionBefore = version();
        ArrayNode request = reversedOrder(count);
        ObjectNode last = (ObjectNode) request.get(count - 1);
        switch (invalid) {
            case DUPLICATE_ID -> last.put("id", request.get(0).path("id").asLong());
            case UNKNOWN_ID -> last.put("id", UNKNOWN_ID);
            case NEGATIVE_SORT_ORDER -> last.put("sortOrder", -1);
        }

        sort(kind, request).andExpect(invalid == InvalidTail.UNKNOWN_ID
                ? status().isNotFound() : status().isBadRequest());

        assertThat(storedRows(kind, count)).isEqualTo(before);
        assertThat(version()).isEqualTo(versionBefore);
    }

    @ParameterizedTest(name = "{0} 第 1001 次写入失败后全部回滚并可重试")
    @EnumSource(Kind.class)
    void lastDatabaseWriteFailureRollsBackAndRetryCanSaveWholeOrder(Kind kind) throws Exception {
        int count = 1001;
        seed(kind, count);
        // 不加测试事务，确保失败响应返回前已经完成业务事务回滚。
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        List<Map<String, Object>> before = storedRows(kind, count);
        int versionBefore = version();
        ArrayNode request = reversedOrder(count);

        // 倒序请求的最后一项才触发数据库约束，前 1000 项写入必须随之回滚。
        jdbc.execute("ALTER TABLE " + kind.table + " ADD CONSTRAINT " + FAILURE_CONSTRAINT
                + " CHECK (id <> " + FIRST_ID + " OR sort_order <> " + (count - 1) * 10 + ")");
        failureConstraintCreated = true;
        try {
            sort(kind, request).andExpect(status().isConflict());
            assertThat(storedRows(kind, count)).isEqualTo(before);
            assertThat(version()).isEqualTo(versionBefore);
        } finally {
            dropFailureConstraint();
        }

        String response = sort(kind, request)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertCompleteOrder(kind, count, response);
        assertThat(version()).isEqualTo(versionBefore + 1);
    }

    @AfterEach
    void removeOnlyCreatedFixtures() {
        dropFailureConstraint();
        if (seededKind != null) {
            jdbc.update("DELETE FROM " + seededKind.table + " WHERE id BETWEEN ? AND ?",
                    FIRST_ID, FIRST_ID + seededCount - 1);
        }
        if (bookmarkCategoryCreated) {
            jdbc.update("DELETE FROM nav_category WHERE id = ?", BOOKMARK_CATEGORY_ID);
        }
    }

    private void seed(Kind kind, int count) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + kind.table + " WHERE id BETWEEN ? AND ?",
                Integer.class, FIRST_ID, FIRST_ID + count - 1)).isZero();
        if (kind == Kind.BOOKMARK) {
            jdbc.update("""
                    INSERT INTO nav_category (id, name, sort_order, visible, created_at, updated_at)
                    VALUES (?, '大列表书签所属分类', 0, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, BOOKMARK_CATEGORY_ID);
            bookmarkCategoryCreated = true;
        }
        seededKind = kind;
        seededCount = count;
        List<Object[]> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(kind == Kind.CATEGORY
                    ? new Object[]{FIRST_ID + index, "大列表分类 " + index, index * 10}
                    : new Object[]{FIRST_ID + index, BOOKMARK_CATEGORY_ID, "大列表书签 " + index,
                    "https://example.com/sort-capacity/" + index, index * 10});
        }
        String insert = kind == Kind.CATEGORY ? """
                INSERT INTO nav_category (id, name, sort_order, visible, created_at, updated_at)
                VALUES (?, ?, ?, TRUE, TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00')
                """ : """
                INSERT INTO nav_bookmark (
                    id, category_id, name, url, sort_order, is_recommend, is_external, visible, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, FALSE, TRUE, TRUE,
                    TIMESTAMP '2020-01-01 00:00:00', TIMESTAMP '2020-01-01 00:00:00')
                """;
        jdbc.batchUpdate(insert, rows);
    }

    private ArrayNode reversedOrder(int count) {
        ArrayNode items = json.createArrayNode();
        for (int index = 0; index < count; index++) {
            items.addObject().put("id", FIRST_ID + count - 1 - index).put("sortOrder", index * 10);
        }
        return items;
    }

    private ResultActions sort(Kind kind, ArrayNode items) throws Exception {
        User admin = users.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin").last("LIMIT 1"));
        return mvc.perform(put("/api/admin/" + kind.path + "/sort")
                .header("Authorization", "Bearer " + tokens.createToken(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(items)));
    }

    private void assertCompleteOrder(Kind kind, int count, String response) throws Exception {
        List<SortValue> expected = IntStream.range(0, count)
                .mapToObj(index -> new SortValue(FIRST_ID + count - 1 - index, index * 10)).toList();
        List<SortValue> databaseOrder = jdbc.query("SELECT id, sort_order FROM " + kind.table
                        + " ORDER BY sort_order, id",
                (row, index) -> new SortValue(row.getLong("id"), row.getInt("sort_order")));
        List<SortValue> responseOrder = new ArrayList<>();
        JsonNode data = json.readTree(response).path("data");
        assertThat(data.isArray()).isTrue();
        data.forEach(row -> responseOrder.add(new SortValue(row.path("id").asLong(), row.path("sortOrder").asInt())));
        assertThat(responseOrder).as("成功响应必须返回数据库中的完整排序").isEqualTo(databaseOrder);
        assertThat(databaseOrder.stream().filter(row -> row.id() >= FIRST_ID && row.id() < FIRST_ID + count).toList())
                .as("全部请求项应按新顺序保存，包含每个查询分段的边界").isEqualTo(expected);
    }

    private List<Map<String, Object>> storedRows(Kind kind, int count) {
        return jdbc.queryForList("SELECT id, sort_order, updated_at FROM " + kind.table
                + " WHERE id BETWEEN ? AND ? ORDER BY id", FIRST_ID, FIRST_ID + count - 1);
    }

    private int version() {
        return jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class);
    }

    private void dropFailureConstraint() {
        if (failureConstraintCreated) {
            jdbc.execute("ALTER TABLE " + seededKind.table + " DROP CONSTRAINT " + FAILURE_CONSTRAINT);
            failureConstraintCreated = false;
        }
    }
}
