package com.example.nav.module.site.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import com.example.nav.module.site.service.SiteConfigService;
import com.example.nav.module.site.vo.SiteConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/site-config")
@SecurityRequirement(name = "bearerAuth")
public class SiteConfigController {

    private final SiteConfigService siteConfigService;

    public SiteConfigController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    @GetMapping
    @Operation(summary = "获取站点配置")
    public Result<SiteConfigVO> getConfig() {
        return Result.success(siteConfigService.getConfig());
    }

    @PutMapping
    @Operation(summary = "更新站点配置")
    public Result<SiteConfigVO> update(@Valid @RequestBody SiteConfigUpdateDTO updateDTO) {
        return Result.success(siteConfigService.update(updateDTO));
    }
}
