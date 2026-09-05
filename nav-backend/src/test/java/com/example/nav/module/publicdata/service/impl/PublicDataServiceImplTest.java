package com.example.nav.module.publicdata.service.impl;

import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.bookmark.vo.BookmarkVO;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.category.vo.CategoryVO;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.site.service.SiteConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicDataServiceImplTest {

    @Test
    void navigationLoadsVisibleBookmarksInOneBatch() {
        SiteConfigService siteConfigService = mock(SiteConfigService.class);
        CategoryService categoryService = mock(CategoryService.class);
        BookmarkService bookmarkService = mock(BookmarkService.class);
        SearchEngineService searchEngineService = mock(SearchEngineService.class);
        CustomLinkService customLinkService = mock(CustomLinkService.class);
        PublicDataServiceImpl service = new PublicDataServiceImpl(
                siteConfigService,
                categoryService,
                bookmarkService,
                searchEngineService,
                customLinkService
        );
        List<CategoryVO> categories = List.of(
                category(1L, "常用", 10),
                category(2L, "开发", 20)
        );
        when(categoryService.listVisible()).thenReturn(categories);
        when(bookmarkService.listVisibleByCategoryIds(List.of(1L, 2L))).thenReturn(List.of(
                bookmark(11L, 1L, "百度", 10),
                bookmark(21L, 2L, "GitHub", 10),
                bookmark(12L, 1L, "Bing", 20)
        ));

        var navigation = service.getNavigation();

        assertThat(navigation).hasSize(2);
        assertThat(navigation.get(0).bookmarks()).extracting(BookmarkVO::name)
                .containsExactly("百度", "Bing");
        assertThat(navigation.get(1).bookmarks()).extracting(BookmarkVO::name)
                .containsExactly("GitHub");
        verify(bookmarkService).listVisibleByCategoryIds(List.of(1L, 2L));
        verify(bookmarkService, never()).listVisible(org.mockito.ArgumentMatchers.anyLong());
    }

    private CategoryVO category(Long id, String name, int sortOrder) {
        return new CategoryVO(id, name, "", sortOrder, true, 0, LocalDateTime.now(), LocalDateTime.now());
    }

    private BookmarkVO bookmark(Long id, Long categoryId, String name, int sortOrder) {
        return new BookmarkVO(
                id, categoryId, name, "https://example.com", "", "", sortOrder,
                false, true, true, LocalDateTime.now(), LocalDateTime.now()
        );
    }
}
