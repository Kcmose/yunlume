package com.example.nav.module.install.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.install.dto.InstallCompleteDTO;
import com.example.nav.module.install.dto.DatabaseConfigureDTO;
import com.example.nav.module.install.dto.DatabaseConnectionDTO;
import com.example.nav.module.install.dto.RedisConfigureDTO;
import com.example.nav.module.install.dto.RedisConnectionDTO;
import com.example.nav.module.install.service.DatabaseSetupService;
import com.example.nav.module.install.service.InstallService;
import com.example.nav.module.install.service.RedisSetupService;
import com.example.nav.module.install.vo.DatabaseConfigureVO;
import com.example.nav.module.install.vo.DatabaseTestVO;
import com.example.nav.module.install.vo.InstallCompleteVO;
import com.example.nav.module.install.vo.InstallEnvironmentVO;
import com.example.nav.module.install.vo.InstallStatusVO;
import com.example.nav.module.install.vo.RedisConfigureVO;
import com.example.nav.module.install.vo.RedisTestVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/install")
public class InstallController {

    private final InstallService installService;
    private final DatabaseSetupService databaseSetupService;
    private final RedisSetupService redisSetupService;

    public InstallController(
            InstallService installService,
            DatabaseSetupService databaseSetupService,
            RedisSetupService redisSetupService
    ) {
        this.installService = installService;
        this.databaseSetupService = databaseSetupService;
        this.redisSetupService = redisSetupService;
    }

    @GetMapping("/status")
    public Result<InstallStatusVO> status(HttpServletResponse response) {
        preventCaching(response);
        return Result.success(installService.status());
    }

    @PostMapping("/complete")
    public Result<InstallCompleteVO> complete(
            @RequestBody InstallCompleteDTO dto,
            HttpServletResponse response
    ) {
        preventCaching(response);
        return Result.success(installService.complete(dto));
    }

    @PostMapping("/check")
    public Result<InstallEnvironmentVO> check(HttpServletResponse response) {
        preventCaching(response);
        return Result.success(installService.check());
    }

    @PostMapping("/database/test")
    public Result<DatabaseTestVO> testDatabase(
            @Valid @RequestBody DatabaseConnectionDTO dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        preventCaching(response);
        databaseSetupService.requireSecureTransport(request);
        return Result.success(databaseSetupService.test(dto));
    }

    @PostMapping("/database/configure")
    public Result<DatabaseConfigureVO> configureDatabase(
            @Valid @RequestBody DatabaseConfigureDTO dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        preventCaching(response);
        databaseSetupService.requireSecureTransport(request);
        return Result.success(databaseSetupService.configure(dto));
    }

    @PostMapping("/redis/test")
    public Result<RedisTestVO> testRedis(
            @Valid @RequestBody RedisConnectionDTO dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        preventCaching(response);
        redisSetupService.requireSecureTransport(request);
        return Result.success(redisSetupService.test(dto));
    }

    @PostMapping("/redis/configure")
    public Result<RedisConfigureVO> configureRedis(
            @Valid @RequestBody RedisConfigureDTO dto,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        preventCaching(response);
        redisSetupService.requireSecureTransport(request);
        return Result.success(redisSetupService.configure(dto));
    }

    private void preventCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
