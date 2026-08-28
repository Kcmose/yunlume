package com.example.nav.module.auth.vo;

import com.example.nav.module.user.vo.UserVO;

public record LoginVO(
        String token,
        String tokenType,
        long expiresIn,
        UserVO user
) {
}
