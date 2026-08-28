package com.example.nav;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PostgreSqlCompatibilityIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void identityColumnsAcceptExplicitIdsForPortableRestore() {
        long explicitId = 9_000_001L;
        jdbcTemplate.update(
                """
                INSERT INTO nav_category (
                    id, name, icon, sort_order, visible, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                explicitId, "导入恢复分类", null, 999, true
        );

        Long storedId = jdbcTemplate.queryForObject(
                "SELECT id FROM nav_category WHERE id = ?",
                Long.class,
                explicitId
        );
        assertThat(storedId).isEqualTo(explicitId);
    }

    @Test
    void bookmarkForeignKeyCascadesWhenCategoryIsRemovedDirectly() {
        long categoryId = 9_000_002L;
        long bookmarkId = 9_000_003L;
        jdbcTemplate.update(
                """
                INSERT INTO nav_category (
                    id, name, sort_order, visible, created_at, updated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                categoryId, "级联恢复分类", 999, true
        );
        jdbcTemplate.update(
                """
                INSERT INTO nav_bookmark (
                    id, category_id, name, url, sort_order,
                    is_recommend, is_external, visible, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                bookmarkId, categoryId, "级联恢复书签", "https://example.com/restore",
                10, false, true, true
        );

        jdbcTemplate.update("DELETE FROM nav_category WHERE id = ?", categoryId);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM nav_bookmark WHERE id = ?",
                Integer.class,
                bookmarkId
        );
        assertThat(remaining).isZero();
    }

    @Test
    void generatedIdentityAdvancesForRegularInserts() {
        Long currentMaximum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM nav_category",
                Long.class
        );
        jdbcTemplate.update(
                """
                INSERT INTO nav_category (
                    name, sort_order, visible, created_at, updated_at
                ) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "自动编号分类", 999, true
        );

        Long generatedId = jdbcTemplate.queryForObject(
                "SELECT id FROM nav_category WHERE name = ?",
                Long.class,
                "自动编号分类"
        );
        assertThat(generatedId).isGreaterThan(currentMaximum);
    }

    @Test
    void onlyOneVisibleDefaultSearchEngineIsAllowed() {
        jdbcTemplate.update("UPDATE search_engine SET is_default = FALSE");
        insertSearchEngine(9_000_004L, "隐藏默认", true, false);
        insertSearchEngine(9_000_005L, "可见默认", true, true);

        assertThatThrownBy(() -> insertSearchEngine(9_000_006L, "第二个可见默认", true, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidBackgroundTypeIsRejectedByDatabaseConstraint() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO site_config (
                    id, site_name, background_type, background_color, font_color,
                    background_effect, music_enabled, subscribe_enabled,
                    top_content_enabled, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                9_000_007L, "非法背景配置", "video", "#000000", "#ffffff",
                false, false, false, true, 0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSearchEngine(long id, String name, boolean defaultEngine, boolean visible) {
        jdbcTemplate.update(
                """
                INSERT INTO search_engine (
                    id, name, search_url, is_default, sort_order, visible,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id, name, "https://example.com/search?q={keyword}",
                defaultEngine, 999, visible
        );
    }
}
