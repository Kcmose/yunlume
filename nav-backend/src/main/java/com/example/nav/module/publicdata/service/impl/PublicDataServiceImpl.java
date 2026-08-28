package com.example.nav.module.publicdata.service.impl;

import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.customlink.vo.CustomLinkVO;
import com.example.nav.module.publicdata.service.PublicDataService;
import com.example.nav.module.publicdata.vo.NavigationVO;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.site.service.SiteConfigService;
import com.example.nav.module.site.vo.SiteConfigVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    public SiteConfigVO getSiteConfig() {
        return siteConfigService.getConfig();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NavigationVO> getNavigation() {
        return categoryService.listVisible().stream()
                .map(category -> new NavigationVO(
                        category.id(), category.name(), category.icon(), category.sortOrder(),
                        category.visible(),
                        bookmarkService.listVisible(category.id())))
                .toList();
    }

    @Override
    public List<SearchEngineVO> getSearchEngines() {
        return searchEngineService.listPublic();
    }

    @Override
    public List<CustomLinkVO> getCustomLinks() {
        return customLinkService.listPublic();
    }
}
