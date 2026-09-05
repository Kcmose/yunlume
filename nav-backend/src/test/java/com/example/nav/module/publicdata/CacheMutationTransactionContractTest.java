package com.example.nav.module.publicdata;

import com.example.nav.module.bookmark.service.impl.BookmarkServiceImpl;
import com.example.nav.module.category.service.impl.CategoryServiceImpl;
import com.example.nav.module.customlink.service.impl.CustomLinkServiceImpl;
import com.example.nav.module.datapackage.service.PortableImportTransactionService;
import com.example.nav.module.search.service.impl.SearchEngineServiceImpl;
import com.example.nav.module.site.service.impl.SiteConfigServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CacheMutationTransactionContractTest {

    @Test
    void everyDatabaseMutationThatInvalidatesPublicDataHasATransactionBoundary() throws Exception {
        assertTransactional(CategoryServiceImpl.class, Map.of(
                "create", 1, "update", 2, "delete", 1, "setVisible", 2, "sort", 1));
        assertTransactional(BookmarkServiceImpl.class, Map.of(
                "create", 1, "update", 2, "delete", 1, "setVisible", 2, "batchMove", 1, "sort", 1));
        assertTransactional(CustomLinkServiceImpl.class, Map.of(
                "create", 1, "update", 2, "delete", 1, "setVisible", 2, "sort", 1));
        assertTransactional(SearchEngineServiceImpl.class, Map.of(
                "create", 1, "update", 2, "delete", 1, "setDefault", 1, "setVisible", 2, "sort", 1));
        assertTransactional(SiteConfigServiceImpl.class, Map.of("update", 1));
        assertTransactional(PortableImportTransactionService.class, Map.of("replaceBusinessData", 10));
    }

    private void assertTransactional(Class<?> type, Map<String, Integer> methods) {
        methods.forEach((name, parameterCount) -> {
            var method = java.util.Arrays.stream(type.getMethods())
                    .filter(candidate -> candidate.getName().equals(name)
                            && candidate.getParameterCount() == parameterCount)
                    .findFirst().orElseThrow();
            assertNotNull(method.getAnnotation(Transactional.class),
                    () -> type.getSimpleName() + "." + name + " must be transactional");
        });
    }
}