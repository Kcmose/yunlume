package com.example.nav;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.bookmark.service.impl.BookmarkServiceImpl;
import com.example.nav.module.category.dto.CategoryCreateDTO;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.category.service.impl.CategoryServiceImpl;
import com.example.nav.module.customlink.dto.CustomLinkDTO;
import com.example.nav.module.customlink.mapper.CustomLinkMapper;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.customlink.service.impl.CustomLinkServiceImpl;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.search.service.SearchEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:business_mutation_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BusinessMutationRegressionTest {

    @Autowired SearchEngineService searches;
    @Autowired CategoryService categories;
    @Autowired BookmarkService bookmarks;
    @Autowired CustomLinkService links;
    @Autowired CategoryMapper categoryMapper;
    @Autowired BookmarkMapper bookmarkMapper;
    @Autowired CustomLinkMapper customLinkMapper;
    @Autowired PublicDataCacheInvalidator invalidator;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    @Transactional
    void clearingSearchOptionalFieldsPersistsNullAndReturnsEmptyText(String cleared) {
        var engine = searches.create(new SearchEngineDTO("清空字段", "OLD_ICON",
                "https://example.com/?q={keyword}", "OLD_PLACEHOLDER", 500, true));

        var result = searches.update(engine.id(), new SearchEngineDTO("清空字段", cleared,
                engine.searchUrl(), cleared, 500, true));

        assertThat(result.icon()).isEmpty();
        assertThat(result.placeholder()).isEmpty();
        var stored = jdbc.queryForMap("SELECT icon, placeholder FROM search_engine WHERE id = ?", engine.id());
        assertThat(stored.get("icon")).isNull();
        assertThat(stored.get("placeholder")).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE - 9, Integer.MAX_VALUE})
    @Transactional
    void searchAppendRejectsOverflowWithoutInsertingNegativeOrder(int maximum) {
        searches.create(new SearchEngineDTO("排序边界", "", "https://example.com/", "", maximum, true));
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM search_engine", Long.class);

        assertConflict(() -> searches.create(new SearchEngineDTO(
                "不能溢出", "", "https://example.com/", "", null, true)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_engine", Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_engine WHERE sort_order < 0", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE - 9, Integer.MAX_VALUE})
    @Transactional
    void customLinkAppendRejectsOverflowWithoutInsertingNegativeOrder(int maximum) {
        links.create(new CustomLinkDTO("排序边界", "/edge", "header", maximum, true));
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM custom_link", Long.class);

        assertConflict(() -> links.create(new CustomLinkDTO("不能溢出", "/new", "header", null, true)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custom_link", Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM custom_link WHERE sort_order < 0", Long.class)).isZero();
    }

    @Test
    @Transactional
    void lastRepresentableAppendRemainsAllowed() {
        searches.create(new SearchEngineDTO("搜索边界", "", "https://example.com/", "", Integer.MAX_VALUE - 10, true));
        links.create(new CustomLinkDTO("链接边界", "/edge", "header", Integer.MAX_VALUE - 10, true));

        assertThat(searches.create(new SearchEngineDTO(
                "最后位置", "", "https://example.com/", "", null, true)).sortOrder()).isEqualTo(Integer.MAX_VALUE);
        assertThat(links.create(new CustomLinkDTO("最后位置", "/last", "header", null, true)).sortOrder())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @Transactional
    void movingCustomLinkToFullPositionDoesNotPartiallyUpdateIt() {
        links.create(new CustomLinkDTO("已满", "/full", "footer", Integer.MAX_VALUE, true));
        var source = links.create(new CustomLinkDTO("原链接", "/source", "header", 0, true));

        assertConflict(() -> links.update(source.id(), new CustomLinkDTO("移动后", "/moved", "footer", null, true)));

        var stored = jdbc.queryForMap("SELECT title, url, position, sort_order FROM custom_link WHERE id = ?", source.id());
        assertThat(stored).containsEntry("title", "原链接").containsEntry("url", "/source")
                .containsEntry("position", "header").containsEntry("sort_order", 0);
    }

    @Test
    void categoryVisibilityPreservesOtherTransactionFields() throws Exception {
        var category = categories.create(new CategoryCreateDTO("旧名称", "OLD", 500, true));
        try {
            var controlled = interleavedMapper(CategoryMapper.class, categoryMapper,
                    () -> jdbc.update("UPDATE nav_category SET name = ?, icon = ?, sort_order = ? WHERE id = ?",
                            "已提交名称", "NEW", 700, category.id()));
            var service = new CategoryServiceImpl(controlled, bookmarkMapper, invalidator);
            var result = transaction(() -> service.setVisible(category.id(), false));

            assertThat(result.name()).isEqualTo("已提交名称");
            assertThat(jdbc.queryForMap("SELECT name, icon, sort_order, visible FROM nav_category WHERE id = ?", category.id()))
                    .containsEntry("name", "已提交名称").containsEntry("icon", "NEW")
                    .containsEntry("sort_order", 700).containsEntry("visible", false);
        } finally {
            categories.delete(category.id());
        }
    }

    @Test
    void bookmarkVisibilityPreservesOtherTransactionFields() throws Exception {
        var category = categories.create(new CategoryCreateDTO("并发分类", "", 500, true));
        var bookmark = bookmarks.create(new BookmarkCreateDTO(category.id(), "旧名称", "https://old.example/",
                "OLD", "旧描述", 10, false, true, true));
        try {
            var controlled = interleavedMapper(BookmarkMapper.class, bookmarkMapper,
                    () -> jdbc.update("UPDATE nav_bookmark SET name = ?, url = ?, description = ?, sort_order = ? WHERE id = ?",
                            "已提交名称", "https://new.example/", "已提交描述", 700, bookmark.id()));
            var service = new BookmarkServiceImpl(controlled, categoryMapper, invalidator);
            var result = transaction(() -> service.setVisible(bookmark.id(), false));

            assertThat(result.name()).isEqualTo("已提交名称");
            assertThat(jdbc.queryForMap("SELECT name, url, description, sort_order, visible FROM nav_bookmark WHERE id = ?", bookmark.id()))
                    .containsEntry("name", "已提交名称").containsEntry("url", "https://new.example/")
                    .containsEntry("description", "已提交描述").containsEntry("sort_order", 700).containsEntry("visible", false);
        } finally {
            bookmarks.delete(bookmark.id());
            categories.delete(category.id());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customLinkTargetedMutationPreservesOtherTransactionFields(boolean sorting) throws Exception {
        var link = links.create(new CustomLinkDTO("旧标题", "/old", "header", 500, true));
        try {
            var controlled = interleavedMapper(CustomLinkMapper.class, customLinkMapper,
                    () -> jdbc.update("UPDATE custom_link SET title = ?, url = ?, position = ? WHERE id = ?",
                            "已提交标题", "/committed", "footer", link.id()));
            var service = new CustomLinkServiceImpl(controlled, invalidator);
            transaction(() -> sorting ? service.sort(List.of(new SortItemDTO(link.id(), 700))) : service.setVisible(link.id(), false));

            var stored = jdbc.queryForMap("SELECT title, url, position, sort_order, visible FROM custom_link WHERE id = ?", link.id());
            assertThat(stored).containsEntry("title", "已提交标题").containsEntry("url", "/committed")
                    .containsEntry("position", "footer").containsEntry("sort_order", sorting ? 700 : 500)
                    .containsEntry("visible", sorting);
        } finally {
            links.delete(link.id());
        }
    }

    private <T> T transaction(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private <T> T interleavedMapper(Class<T> type, T delegate, Runnable otherWrite) {
        var interleaved = new AtomicBoolean();
        var delegateAnswer = delegatesTo(delegate);
        return mock(type, invocation -> {
            String name = invocation.getMethod().getName();
            if ((name.equals("updateVisible") || name.equals("updateSortOrder") || name.equals("updateById"))
                    && interleaved.compareAndSet(false, true)) {
                // 精确停在写入前，让另一个真实数据库事务先提交；Mapper/SQL 仍使用实际实现。
                var worker = Executors.newSingleThreadExecutor();
                try {
                    worker.submit(() -> transaction(() -> { otherWrite.run(); return null; }))
                            .get(10, TimeUnit.SECONDS);
                } finally {
                    worker.shutdownNow();
                }
            }
            return delegateAnswer.answer(invocation);
        });
    }

    private void assertConflict(Supplier<?> operation) {
        assertThatThrownBy(operation::get).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
