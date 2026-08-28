package com.example.nav.module.auth.service;

import com.example.nav.module.auth.dto.LoginDTO;
import com.example.nav.module.auth.dto.ChangePasswordDTO;
import com.example.nav.module.auth.vo.LoginVO;
import com.example.nav.module.user.vo.UserVO;

public interface AuthService {

    LoginVO login(LoginDTO loginDTO);

    UserVO profile(String username);

    void changePassword(String username, ChangePasswordDTO changePasswordDTO);

    void logoutAll(String username);
}
