package com.example.nav.module.publicdata;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicDataCacheGenerationStoreTest {

    @Test
    void durableAuthorityRequiresExactlyOneNonNullSiteConfigGeneration() {
        var dataSource = database("jdbc:h2:mem:generation-singleton;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS site_config");
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER, updated_at TIMESTAMP)");
        var store = new PublicDataCacheGenerationStore(jdbc);

        assertThrows(com.example.nav.common.exception.BusinessException.class, store::current);
        jdbc.update("INSERT INTO site_config(id, version) VALUES (1, 12)");
        assertEquals(12L, store.current());
        jdbc.update("INSERT INTO site_config(id, version) VALUES (2, 13)");
        assertThrows(com.example.nav.common.exception.BusinessException.class, store::current);
        jdbc.update("DELETE FROM site_config WHERE id = 2");
        jdbc.update("UPDATE site_config SET version = NULL WHERE id = 1");
        assertThrows(com.example.nav.common.exception.BusinessException.class, store::current);
    }

    @Test
    void negativeDurableGenerationFailsClosedBeforeAnyMutation() {
        var dataSource = database("jdbc:h2:mem:generation-negative;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS site_config");
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO site_config(id, version) VALUES (1, -1)");
        var store = new PublicDataCacheGenerationStore(jdbc);

        assertThrows(com.example.nav.common.exception.BusinessException.class, store::current);
        assertThrows(com.example.nav.common.exception.BusinessException.class, store::advance);
        assertEquals(-1, jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class));
    }

    @Test
    void maximumDurableGenerationCannotBeIncrementedOrMutated() {
        var dataSource = database("jdbc:h2:mem:generation-maximum;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS site_config");
        jdbc.execute("CREATE TABLE site_config (id BIGINT PRIMARY KEY, version INTEGER NOT NULL, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO site_config(id, version) VALUES (1, 2147483647)");
        var store = new PublicDataCacheGenerationStore(jdbc);

        assertThrows(com.example.nav.common.exception.BusinessException.class, store::advance);
        assertThrows(com.example.nav.common.exception.BusinessException.class,
                () -> store.advanceTo(2147483648L));
        assertEquals(Integer.MAX_VALUE,
                jdbc.queryForObject("SELECT version FROM site_config WHERE id = 1", Integer.class));
    }

    private DataSource database(String h2Url) {
        String postgresUrl = System.getenv("PUBLIC_CACHE_PG_URL");
        if (postgresUrl == null || postgresUrl.isBlank()) {
            return new DriverManagerDataSource(h2Url, "sa", "");
        }
        return new DriverManagerDataSource(
                postgresUrl,
                System.getenv().getOrDefault("PUBLIC_CACHE_PG_USERNAME", "postgres"),
                System.getenv().getOrDefault("PUBLIC_CACHE_PG_PASSWORD", "postgres"));
    }
}
