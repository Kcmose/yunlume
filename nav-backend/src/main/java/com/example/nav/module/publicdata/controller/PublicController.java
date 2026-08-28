package com.example.nav.module.publicdata.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.customlink.vo.CustomLinkVO;
import com.example.nav.module.publicdata.service.PublicDataService;
import com.example.nav.module.publicdata.vo.NavigationVO;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.site.vo.SiteConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/public")
public class PublicController {

    private final PublicDataService publicDataService;

    public PublicController(PublicDataService publicDataService) {
        this.publicDataService = publicDataService;
    }

    @GetMapping("/site-config")
    @Operation(summary = "公开站点配置")
    public Result<SiteConfigVO> siteConfig() {
        return Result.success(publicDataService.getSiteConfig());
    }

    @GetMapping("/navigation")
    @Operation(summary = "公开导航分类和书签")
    public Result<List<NavigationVO>> navigation() {
        return Result.success(publicDataService.getNavigation());
    }

    @GetMapping("/search-engines")
    @Operation(summary = "公开搜索引擎")
    public Result<List<SearchEngineVO>> searchEngines() {
        return Result.success(publicDataService.getSearchEngines());
    }

    @GetMapping("/custom-links")
    @Operation(summary = "公开头部和底部链接")
    public Result<List<CustomLinkVO>> customLinks() {
        return Result.success(publicDataService.getCustomLinks());
    }
}
