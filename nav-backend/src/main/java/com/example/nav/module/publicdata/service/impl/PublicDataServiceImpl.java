package com.example.nav.module.publicdata.service.impl;

import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.bookmark.vo.BookmarkVO;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.customlink.vo.CustomLinkVO;
import com.example.nav.module.publicdata.service.PublicDataService;
import com.example.nav.module.publicdata.PublicDataCacheNames;
import com.example.nav.module.publicdata.vo.NavigationVO;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.site.service.SiteConfigService;
import com.example.nav.module.site.vo.SiteConfigVO;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PublicDataServiceImpl implements PublicDataService {

    private final SiteConfigService siteConfigService;
    private final CategoryService categoryService;
    private final BookmarkService bookmarkService;
    private final SearchEngineService searchEngineService;
    private final CustomLinkService customLinkService;

    public PublicDataServiceImpl(
            SiteConfigService siteConfigService,
            CategoryService categoryService,
            BookmarkService bookmarkService,
            SearchEngineService searchEngineService,
            CustomLinkService customLinkService
    ) {
        this.siteConfigService = siteConfigService;
        this.categoryService = categoryService;
        this.bookmarkService = bookmarkService;
        this.searchEngineService = searchEngineService;
        this.customLinkService = customLinkService;
    }

    @Override
    @Cacheable(cacheNames = PublicDataCacheNames.SITE_CONFIG,
            key = "@publicDataCacheVersion.current('publicSiteConfig')")
    public SiteConfigVO getSiteConfig() {
        return siteConfigService.getConfig();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = PublicDataCacheNames.NAVIGATION,
            key = "@publicDataCacheVersion.current('publicNavigation')")
    public List<NavigationVO> getNavigation() {
        var categories = categoryService.listVisible();
        Map<Long, List<BookmarkVO>> bookmarksByCategory =
                bookmarkService.listVisibleByCategoryIds(categories.stream().map(category -> category.id()).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                bookmark -> bookmark.categoryId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
        return categories.stream()
                .map(category -> new NavigationVO(
                        category.id(), category.name(), category.icon(), category.sortOrder(),
                        category.visible(),
                        bookmarksByCategory.getOrDefault(category.id(), List.of())))
                .toList();
    }

    @Override
    @Cacheable(cacheNames = PublicDataCacheNames.SEARCH_ENGINES,
            key = "@publicDataCacheVersion.current('publicSearchEngines')")
    public List<SearchEngineVO> getSearchEngines() {
        return searchEngineService.listPublic();
    }

    @Override
    @Cacheable(cacheNames = PublicDataCacheNames.CUSTOM_LINKS,
            key = "@publicDataCacheVersion.current('publicCustomLinks')")
    public List<CustomLinkVO> getCustomLinks() {
        return customLinkService.listPublic();
    }
}
