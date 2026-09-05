package com.example.nav.module.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryServiceImplTest {

    @Test
    void visibleCategoriesDoNotCountBookmarksPerCategory() {
        CategoryMapper categoryMapper = mock(CategoryMapper.class);
        BookmarkMapper bookmarkMapper = mock(BookmarkMapper.class);
        PublicDataCacheInvalidator cacheInvalidator = mock(PublicDataCacheInvalidator.class);
        CategoryServiceImpl service = new CategoryServiceImpl(categoryMapper, bookmarkMapper, cacheInvalidator);
        Category category = new Category();
        category.setId(7L);
        category.setName("常用");
        category.setIcon("");
        category.setSortOrder(10);
        category.setVisible(true);
        category.setCreatedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());
        when(categoryMapper.selectList(any())).thenReturn(List.of(category));

        var result = service.listVisible();

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(7L);
            assertThat(item.bookmarkCount()).isZero();
        });
        verify(bookmarkMapper, never()).selectCount(org.mockito.ArgumentMatchers.<Wrapper<Bookmark>>any());
    }
}
