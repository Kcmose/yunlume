package com.example.nav.module.auth.controller;

import com.example.nav.common.result.Result;
import com.example.nav.module.auth.dto.LoginDTO;
import com.example.nav.module.auth.dto.ChangePasswordDTO;
import com.example.nav.module.auth.service.AuthService;
import com.example.nav.module.auth.vo.LoginVO;
import com.example.nav.module.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "管理员登录")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        return Result.success(authService.login(loginDTO));
    }

    @PostMapping("/logout")
    @Operation(summary = "管理员退出（客户端丢弃无状态 JWT）")
    @SecurityRequirement(name = "bearerAuth")
    public Result<Void> logout() {
        return Result.success();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "退出当前管理员的全部登录会话")
    @SecurityRequirement(name = "bearerAuth")
    public Result<Void> logoutAll(Authentication authentication) {
        authService.logoutAll(authentication.getName());
        return Result.success();
    }

    @PutMapping("/password")
    @Operation(summary = "修改当前管理员密码")
    @SecurityRequirement(name = "bearerAuth")
    public Result<Void> changePassword(
            Authentication authentication,
            @RequestBody ChangePasswordDTO changePasswordDTO
    ) {
        authService.changePassword(authentication.getName(), changePasswordDTO);
        return Result.success();
    }

    @GetMapping("/profile")
    @Operation(summary = "获取当前管理员")
    @SecurityRequirement(name = "bearerAuth")
    public Result<UserVO> profile(Authentication authentication) {
        return Result.success(authService.profile(authentication.getName()));
    }
}
