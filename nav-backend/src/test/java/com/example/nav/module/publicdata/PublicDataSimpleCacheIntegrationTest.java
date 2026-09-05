package com.example.nav.module.publicdata;

import com.example.nav.module.publicdata.service.PublicDataService;
import com.example.nav.module.search.vo.SearchEngineVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cache.type=simple",
        "spring.datasource.url=jdbc:h2:mem:public_simple_cache;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class PublicDataSimpleCacheIntegrationTest {

    private static final String[] CACHE_NAMES = {
            PublicDataCacheNames.SITE_CONFIG, PublicDataCacheNames.NAVIGATION,
            PublicDataCacheNames.SEARCH_ENGINES, PublicDataCacheNames.CUSTOM_LINKS
    };

    @Autowired CacheManager cacheManager;
    @Autowired PublicDataService publicData;
    @Autowired PublicDataCacheVersion version;
    @Autowired PublicDataCacheGenerationStore generations;
    @Autowired PublicDataCacheInvalidator invalidator;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearValues() {
        for (String name : CACHE_NAMES) {
            assertThat(cacheManager.getCache(name)).isInstanceOf(PublicDataGenerationCache.class);
            cacheManager.getCache(name).clear();
        }
    }

    @Test
    void repeatedCommittedMutationsKeepOnlyOneActualGenerationKeyPerPublicCache() {
        for (int iteration = 0; iteration < 30; iteration++) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> invalidator.invalidate(CACHE_NAMES));
            publicData.getSiteConfig();
            publicData.getNavigation();
            publicData.getSearchEngines();
            publicData.getCustomLinks();

            String current = Long.toString(generations.current());
            for (String name : CACHE_NAMES) {
                assertThat(((Map<?, ?>) cacheManager.getCache(name).getNativeCache()).keySet()).isEqualTo(Set.of(current));
            }
        }
    }

    @Test
    void rollbackDoesNotAdvanceOrEvictCommittedLocalCache() {
        publicData.getSearchEngines();
        String before = version.current(PublicDataCacheNames.SEARCH_ENGINES);
        var cache = cacheManager.getCache(PublicDataCacheNames.SEARCH_ENGINES);
        Object original = cache.get(before).get();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE search_engine SET name = 'uncommitted'");
            invalidator.invalidate(PublicDataCacheNames.SEARCH_ENGINES);
            assertThat(publicData.getSearchEngines()).allMatch(engine -> engine.name().equals("uncommitted"));
            status.setRollbackOnly();
        });

        assertThat(version.current(PublicDataCacheNames.SEARCH_ENGINES)).isEqualTo(before);
        assertThat(cache.get(before).get()).isSameAs(original);
        assertThat(((Map<?, ?>) cache.getNativeCache()).keySet()).isEqualTo(Set.of(before));
    }

    @Test
    void oldReadFinishingAfterCommittedInvalidationCannotRefillTheCache() throws Exception {
        var cache = cacheManager.getCache(PublicDataCacheNames.SEARCH_ENGINES);
        String oldKey = version.current(PublicDataCacheNames.SEARCH_ENGINES);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        List<SearchEngineVO> oldValue = List.of();
        List<SearchEngineVO> currentValue = List.of(new SearchEngineVO(
                999L, "current", "", "https://example.com/", "", true, 0, true));
        try {
            var staleRead = worker.submit(() -> cache.get(oldKey, () -> {
                started.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("读取未释放");
                return oldValue;
            }));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> invalidator.invalidate(PublicDataCacheNames.SEARCH_ENGINES));
            String currentKey = version.current(PublicDataCacheNames.SEARCH_ENGINES);
            cache.put(currentKey, currentValue);
            release.countDown();

            assertThat(staleRead.get(10, TimeUnit.SECONDS)).isEqualTo(oldValue);
            cache.put(oldKey, oldValue);
            cache.putIfAbsent(oldKey, oldValue);
            assertThat(cache.get(oldKey)).isNull();
            assertThat(cache.get(currentKey).get()).isEqualTo(currentValue);
            assertThat(((Map<?, ?>) cache.getNativeCache()).keySet()).isEqualTo(Set.of(currentKey));
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
