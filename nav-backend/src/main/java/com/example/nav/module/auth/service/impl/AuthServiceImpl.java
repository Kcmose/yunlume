package com.example.nav.module.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.common.security.PasswordPolicy;
import com.example.nav.module.auth.dto.ChangePasswordDTO;
import com.example.nav.module.auth.dto.LoginDTO;
import com.example.nav.module.auth.service.AuthService;
import com.example.nav.module.auth.vo.LoginVO;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.module.user.vo.UserVO;
import com.example.nav.security.JwtTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;

    public AuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtTokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, loginDTO.username())
                .last("LIMIT 1"));
        if (loginDTO.password().getBytes(StandardCharsets.UTF_8).length > 72
                || user == null || !Boolean.TRUE.equals(user.getStatus())
                || !passwordEncoder.matches(loginDTO.password(), user.getPassword())) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }

        String token = tokenService.createToken(user);
        return new LoginVO(token, "Bearer", tokenService.getExpirationSeconds(), toVO(user));
    }

    @Override
    public UserVO profile(String username) {
        return toVO(requireActiveUser(username));
    }

    @Override
    @Transactional
    public void changePassword(String username, ChangePasswordDTO changePasswordDTO) {
        if (changePasswordDTO == null) {
            throw BusinessException.badRequest("密码参数不能为空");
        }

        String currentPassword = changePasswordDTO.currentPassword();
        if (currentPassword == null || currentPassword.isEmpty()
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw BusinessException.badRequest("当前密码错误");
        }

        User user = requireActiveUser(username);
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw BusinessException.badRequest("当前密码错误");
        }

        String newPassword = changePasswordDTO.newPassword();
        String confirmPassword = changePasswordDTO.confirmPassword();
        if (newPassword == null || confirmPassword == null) {
            throw BusinessException.badRequest("新密码和确认密码不能为空");
        }
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw BusinessException.badRequest("两次输入的新密码不一致");
        }

        validateNewPassword(user, newPassword);
        String encodedPassword = passwordEncoder.encode(newPassword);
        int updated = userMapper.updatePasswordAndIncrementTokenVersion(
                user.getId(),
                user.getTokenVersion() == null ? 0 : user.getTokenVersion(),
                user.getPassword(),
                encodedPassword,
                LocalDateTime.now());
        if (updated != 1) {
            throw BusinessException.conflict("密码已被其他操作修改，请重新登录后再试");
        }
    }

    @Override
    @Transactional
    public void logoutAll(String username) {
        User user = requireActiveUser(username);
        int updated = userMapper.incrementTokenVersion(user.getId(), LocalDateTime.now());
        if (updated != 1) {
            throw BusinessException.conflict("登录状态已发生变化，请重新登录后再试");
        }
    }

    private UserVO toVO(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole());
    }

    private User requireActiveUser(String username) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .eq(User::getStatus, true)
                .last("LIMIT 1"));
        if (user == null) {
            throw BusinessException.unauthorized("登录用户不存在或已停用");
        }
        return user;
    }

    private void validateNewPassword(User user, String newPassword) {
        PasswordPolicy.findViolation(user.getUsername(), newPassword)
                .ifPresent(message -> {
                    throw BusinessException.badRequest(message);
                });
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw BusinessException.badRequest("新密码不能与当前密码相同");
        }
    }
}
