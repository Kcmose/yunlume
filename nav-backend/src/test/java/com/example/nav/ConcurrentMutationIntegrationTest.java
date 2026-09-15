package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.bookmark.dto.BookmarkBatchMoveDTO;
import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.bookmark.dto.BookmarkUpdateDTO;
import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.category.dto.CategoryCreateDTO;
import com.example.nav.module.category.dto.CategoryUpdateDTO;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.customlink.dto.CustomLinkDTO;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 两个独立连接执行真实 Service、Mapper 和事务；同一组用例也在 PostgreSQL 14/18 运行。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:concurrent_mutation_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ConcurrentMutationIntegrationTest extends ConcurrentDatabaseTestSupport {
    @Autowired CategoryService categories;
    @Autowired BookmarkService bookmarks;
    @Autowired CustomLinkService links;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JwtTokenService tokens;
    private final Deque<Runnable> cleanup = new ArrayDeque<>();

    enum Kind {
        CATEGORY("nav_category", "categories", "name"),
        BOOKMARK("nav_bookmark", "bookmarks", "name"),
        LINK("custom_link", "custom-links", "title");
        final String table;
        final String path;
        final String text;
        Kind(String table, String path, String text) { this.table = table; this.path = path; this.text = text; }
    }

    @AfterEach
    void cleanCreatedRows() {
        while (!cleanup.isEmpty()) cleanup.pop().run();
    }

    static Stream<Arguments> requestOrders() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(true, false)
                .map(editFirst -> Arguments.of(kind, editFirst)));
    }

    @ParameterizedTest
    @MethodSource("requestOrders")
    void overlappingEditAndVisibilitySortPreserveBothCommitOrders(Kind kind, boolean editFirst) throws Exception {
        long id = create(kind);
        int initialVersion = version();
        Runnable edit = () -> edit(kind, id, "已编辑");
        Runnable targeted = () -> { visible(kind, id, false); sort(kind, id, 777); };
        overlap(editFirst ? edit : targeted, editFirst ? targeted : edit, false);
        Map<String, Object> stored = row(kind, id);
        assertThat(stored.get(kind.text)).isEqualTo("已编辑");
        assertThat(stored.get("visible")).isEqualTo(false);
        assertThat(stored.get("sort_order")).isEqualTo(777);
        assertThat(version()).isEqualTo(initialVersion + 3);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void legacyEditWithOmittedFlagsCannotOverwriteAnEarlierUncommittedToggle(Kind kind) throws Exception {
        long id = create(kind);
        var before = row(kind, id);
        Runnable legacyEdit = () -> {
            switch (kind) {
                case CATEGORY -> categories.update(id, new CategoryUpdateDTO("完整编辑", "OLD", null, null));
                case BOOKMARK -> bookmarks.update(id, new BookmarkUpdateDTO(
                        ((Number) before.get("category_id")).longValue(), "完整编辑", "https://example.com/",
                        "OLD", "OLD_DESC", null, null, null, null));
                case LINK -> links.update(id, new CustomLinkDTO("完整编辑", "/old", "header", null, null));
            }
        };
        overlap(() -> { visible(kind, id, false); sort(kind, id, 654); }, legacyEdit, false);
        assertThat(row(kind, id).get("visible")).isEqualTo(false);
        assertThat(row(kind, id).get("sort_order")).isEqualTo(654);
        assertThat(row(kind, id).get(kind.text)).isEqualTo("完整编辑");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void rolledBackFirstWriteCannotLeakAndWaitingEditCanRecover(Kind kind) throws Exception {
        long id = create(kind);
        int initialVersion = version();
        overlap(() -> visible(kind, id, false), () -> edit(kind, id, "恢复编辑"), true);
        assertThat(row(kind, id).get("visible")).isEqualTo(true);
        assertThat(row(kind, id).get(kind.text)).isEqualTo("恢复编辑");
        assertThat(version()).isEqualTo(initialVersion + 1);
        visible(kind, id, false);
        assertThat(row(kind, id).get(kind.text)).isEqualTo("恢复编辑");
        assertThat(row(kind, id).get("visible")).isEqualTo(false);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void generationFailureRollsBackWholeEditAndRetrySucceeds(Kind kind) {
        long id = create(kind);
        Map<String, Object> before = row(kind, id);
        int previousVersion = version();
        try {
            jdbc.update("UPDATE site_config SET version=? WHERE id=1", Integer.MAX_VALUE);
            assertThatThrownBy(() -> edit(kind, id, "不应提交"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(row(kind, id)).isEqualTo(before);
            assertThat(version()).isEqualTo(Integer.MAX_VALUE);
        } finally {
            jdbc.update("UPDATE site_config SET version=? WHERE id=1", previousVersion);
        }
        edit(kind, id, "重试成功");
        assertThat(row(kind, id).get(kind.text)).isEqualTo("重试成功");
        assertThat(version()).isEqualTo(previousVersion + 1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void apiPartialEditReturnsCurrentFieldsAndDeletionIsNotSuccess(Kind kind) throws Exception {
        long id = create(kind);
        visible(kind, id, false);
        sort(kind, id, 789);
        mvc.perform(put(endpoint(kind, id)).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + kind.text + "\":\"部分修改\",\"visible\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data." + kind.text).value("部分修改"))
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(jsonPath("$.data.sortOrder").value(789));
        assertThat(row(kind, id).get(kind.text)).isEqualTo("部分修改");
        delete(kind, id);
        cleanup.pop();
        int previousVersion = version();
        mvc.perform(put(endpoint(kind, id)).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"" + kind.text + "\":\"过期编辑\"}"))
                .andExpect(status().isNotFound());
        assertThat(version()).isEqualTo(previousVersion);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void creationStillRequiresFieldsAndInvalidProvidedTextDoesNotMutate(Kind kind) throws Exception {
        long id = create(kind);
        Map<String, Object> before = row(kind, id);
        int previousVersion = version();
        mvc.perform(post("/api/admin/" + kind.path).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("", "  ", "\u2003", "\u3000")) {
            mvc.perform(put(endpoint(kind, id)).header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"" + kind.text + "\":\"" + invalid + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(row(kind, id)).isEqualTo(before);
        assertThat(version()).isEqualTo(previousVersion);
    }

    @ParameterizedTest
    @MethodSource("requestOrders")
    void twoExplicitChangesToSameFieldFollowCommitOrder(Kind kind, boolean firstValue) throws Exception {
        long id = create(kind);
        overlap(() -> visible(kind, id, firstValue), () -> updateVisibility(kind, id, !firstValue), false);
        assertThat(row(kind, id).get("visible")).isEqualTo(!firstValue);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void bookmarkEditAndBatchMovePreserveCategorySortAndFlags(boolean editFirst) throws Exception {
        long target = create(Kind.CATEGORY);
        long id = create(Kind.BOOKMARK);
        Runnable edit = () -> edit(Kind.BOOKMARK, id, "独立名称");
        Runnable move = () -> {
            bookmarks.batchMove(new BookmarkBatchMoveDTO(List.of(id), target));
            bookmarks.update(id, new BookmarkUpdateDTO(null, null, null, null, null, null, true, false, false));
        };
        overlap(editFirst ? edit : move, editFirst ? move : edit, false);
        var result = row(Kind.BOOKMARK, id);
        assertThat(((Number) result.get("category_id")).longValue()).isEqualTo(target);
        assertThat(result.get("sort_order")).isEqualTo(0);
        assertThat(result.get("name")).isEqualTo("独立名称");
        assertThat(result.get("is_recommend")).isEqualTo(true);
        assertThat(result.get("is_external")).isEqualTo(false);
        assertThat(result.get("visible")).isEqualTo(false);
    }

    @Test
    void customLinkAutomaticSortUsesPositionAfterConcurrentMoveCommits() throws Exception {
        long id = create(Kind.LINK);
        // 首个请求移至 footer 并指定排序；等待中的同位置请求应保留它，不能重新追加。
        overlap(() -> links.update(id, new CustomLinkDTO(null, null, "footer", 912, null)),
                () -> links.update(id, new CustomLinkDTO("后续编辑", null, "footer", null, null)), false);
        var result = row(Kind.LINK, id);
        assertThat(result.get("position")).isEqualTo("footer");
        assertThat(result.get("sort_order")).isEqualTo(912);
        assertThat(result.get("title")).isEqualTo("后续编辑");
    }

    @Test
    void optionalBookmarkTextCanClearWithoutChangingUrlOrCategory() throws Exception {
        long id = create(Kind.BOOKMARK);
        var before = row(Kind.BOOKMARK, id);
        mvc.perform(put(endpoint(Kind.BOOKMARK, id)).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"icon\":\"\",\"description\":\"\"}"))
                .andExpect(status().isOk());
        var after = row(Kind.BOOKMARK, id);
        assertThat(after.get("icon")).isEqualTo("");
        assertThat(after.get("description")).isEqualTo("");
        assertThat(after.get("url")).isEqualTo(before.get("url"));
        assertThat(after.get("category_id")).isEqualTo(before.get("category_id"));
    }

    private long create(Kind kind) {
        long id;
        if (kind == Kind.CATEGORY) {
            id = categories.create(new CategoryCreateDTO("原名称", "OLD", 10, true)).id();
        } else if (kind == Kind.BOOKMARK) {
            long category = create(Kind.CATEGORY);
            id = bookmarks.create(new BookmarkCreateDTO(category, "原名称", "https://example.com/", "OLD",
                    "OLD_DESC", 10, false, true, true)).id();
        } else {
            id = links.create(new CustomLinkDTO("原名称", "/old", "header", 10, true)).id();
        }
        cleanup.push(() -> delete(kind, id));
        return id;
    }

    private Object edit(Kind kind, long id, String value) {
        return switch (kind) {
            case CATEGORY -> categories.update(id, new CategoryUpdateDTO(value, null, null, null));
            case BOOKMARK -> bookmarks.update(id, new BookmarkUpdateDTO(null, value, null, null, null, null, null, null, null));
            case LINK -> links.update(id, new CustomLinkDTO(value, null, null, null, null));
        };
    }

    private void updateVisibility(Kind kind, long id, boolean value) {
        switch (kind) {
            case CATEGORY -> categories.update(id, new CategoryUpdateDTO(null, null, null, value));
            case BOOKMARK -> bookmarks.update(id, new BookmarkUpdateDTO(null, null, null, null, null, null, null, null, value));
            case LINK -> links.update(id, new CustomLinkDTO(null, null, null, null, value));
        }
    }

    private void visible(Kind kind, long id, boolean value) {
        switch (kind) {
            case CATEGORY -> categories.setVisible(id, value);
            case BOOKMARK -> bookmarks.setVisible(id, value);
            case LINK -> links.setVisible(id, value);
        }
    }

    private void sort(Kind kind, long id, int value) {
        var items = List.of(new SortItemDTO(id, value));
        switch (kind) {
            case CATEGORY -> categories.sort(items);
            case BOOKMARK -> bookmarks.sort(items);
            case LINK -> links.sort(items);
        }
    }

    private void delete(Kind kind, long id) {
        switch (kind) {
            case CATEGORY -> categories.delete(id);
            case BOOKMARK -> bookmarks.delete(id);
            case LINK -> links.delete(id);
        }
    }

    private Map<String, Object> row(Kind kind, long id) {
        return jdbc.queryForMap("SELECT * FROM " + kind.table + " WHERE id=?", id);
    }

    private int version() { return jdbc.queryForObject("SELECT version FROM site_config WHERE id=1", Integer.class); }
    private String endpoint(Kind kind, long id) { return "/api/admin/" + kind.path + "/" + id; }
    private String bearer() {
        return "Bearer " + tokens.createToken(users.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin").last("LIMIT 1")));
    }

    private void overlap(Runnable firstAction, Runnable secondAction, boolean rollbackFirst) throws Exception {
        var applied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var secondSession = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                firstAction.run();
                applied.countDown();
                await(release);
                if (rollbackFirst) tx.setRollbackOnly();
            }));
            assertThat(applied.await(10, TimeUnit.SECONDS)).as("首事务完成写入但尚未提交").isTrue();
            var second = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                secondSession.set(databaseSessionId());
                started.countDown();
                secondAction.run();
            }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            awaitBlockedSession(secondSession.get());
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发测试等待超时");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发测试被中断", error);
        }
    }
}
