package com.example.nav.module.publicdata.service;

import com.example.nav.module.customlink.vo.CustomLinkVO;
import com.example.nav.module.publicdata.vo.NavigationVO;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.site.vo.SiteConfigVO;

import java.util.List;

public interface PublicDataService {

    SiteConfigVO getSiteConfig();

    List<NavigationVO> getNavigation();

    List<SearchEngineVO> getSearchEngines();

    List<CustomLinkVO> getCustomLinks();
}
