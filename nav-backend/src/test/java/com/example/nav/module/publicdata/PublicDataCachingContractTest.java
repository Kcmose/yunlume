package com.example.nav.module.publicdata;

import com.example.nav.module.bookmark.service.impl.BookmarkServiceImpl;
import com.example.nav.module.bookmark.vo.BookmarkVO;
import com.example.nav.module.category.service.impl.CategoryServiceImpl;
import com.example.nav.module.customlink.service.impl.CustomLinkServiceImpl;
import com.example.nav.module.customlink.vo.CustomLinkVO;
import com.example.nav.module.datapackage.service.PortableImportTransactionService;
import com.example.nav.module.publicdata.service.impl.PublicDataServiceImpl;
import com.example.nav.module.publicdata.vo.NavigationVO;
import com.example.nav.module.search.service.impl.SearchEngineServiceImpl;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.site.service.impl.SiteConfigServiceImpl;
import com.example.nav.module.site.vo.SiteConfigVO;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDataCachingContractTest {

    @Test
    void publicReadsAreCachedAndMutationsUseTheBestEffortInvalidator() {
        assertCached(PublicDataServiceImpl.class, "getSiteConfig", "publicSiteConfig");
        assertCached(PublicDataServiceImpl.class, "getNavigation", "publicNavigation");
        assertCached(PublicDataServiceImpl.class, "getSearchEngines", "publicSearchEngines");
        assertCached(PublicDataServiceImpl.class, "getCustomLinks", "publicCustomLinks");

        assertUsesBestEffortInvalidator(SiteConfigServiceImpl.class);
        assertUsesBestEffortInvalidator(CategoryServiceImpl.class);
        assertUsesBestEffortInvalidator(BookmarkServiceImpl.class);
        assertUsesBestEffortInvalidator(SearchEngineServiceImpl.class);
        assertUsesBestEffortInvalidator(CustomLinkServiceImpl.class);
        assertUsesBestEffortInvalidator(PortableImportTransactionService.class);
    }

    @Test
    void cachedValuesSupportTheDefaultRedisSerializer() {
        assertThat(Serializable.class).isAssignableFrom(SiteConfigVO.class);
        assertThat(Serializable.class).isAssignableFrom(NavigationVO.class);
        assertThat(Serializable.class).isAssignableFrom(BookmarkVO.class);
        assertThat(Serializable.class).isAssignableFrom(SearchEngineVO.class);
        assertThat(Serializable.class).isAssignableFrom(CustomLinkVO.class);
    }

    private void assertCached(Class<?> type, String methodName, String cacheName) {
        Cacheable annotation = method(type, methodName).getAnnotation(Cacheable.class);
        assertThat(annotation).as(type.getSimpleName() + "." + methodName).isNotNull();
        assertThat(annotation.cacheNames()).contains(cacheName);
    }

    private void assertUsesBestEffortInvalidator(Class<?> type) {
        assertThat(Arrays.stream(type.getDeclaredFields())
                .anyMatch(field -> field.getType() == PublicDataCacheInvalidator.class))
                .as(type.getSimpleName() + " invalidator dependency")
                .isTrue();
    }

    private Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
