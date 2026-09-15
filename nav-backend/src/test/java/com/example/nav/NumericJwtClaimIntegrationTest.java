package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.config.JwtProperties;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:numeric_jwt_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class NumericJwtClaimIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JwtTokenService tokens;
    @Autowired JwtProperties properties;

    @ParameterizedTest
    @ValueSource(strings = {"userIdFraction", "userIdFloat", "versionFraction", "versionFloat", "userIdOverflow", "userIdBigIntegerOverflow"})
    void signedNonIntegralClaimsCannotAliasAnIntegerAndValidSessionStillWorks(String scenario) throws Exception {
        User admin = users.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin").last("LIMIT 1"));
        int version = admin.getTokenVersion() == null ? 0 : admin.getTokenVersion();
        Number userId = admin.getId();
        Number tokenVersion = version;
        switch (scenario) {
            case "userIdFraction" -> userId = new BigDecimal(admin.getId() + ".00000000000000001");
            case "userIdFloat" -> userId = new BigDecimal(admin.getId() + ".0");
            case "versionFraction" -> tokenVersion = new BigDecimal(version + ".00000000000000001");
            case "versionFloat" -> tokenVersion = new BigDecimal(version + ".0");
            case "userIdOverflow" -> userId = new BigDecimal("9.223372036854776E18");
            case "userIdBigIntegerOverflow" -> userId = new BigInteger("9223372036854775808");
            default -> throw new AssertionError(scenario);
        }
        String invalid = Jwts.builder().subject(admin.getUsername()).claim("role", admin.getRole())
                .claim("userId", userId).claim("ver", tokenVersion)
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
        if (scenario.equals("userIdBigIntegerOverflow")) {
            assertThat(tokens.parse(invalid).get("userId")).isInstanceOf(BigInteger.class);
        }
        String valid = tokens.createToken(admin);
        // 正常请求在非法请求前后都保持可用，非法解析不能污染后续认证状态或令牌版本。
        mvc.perform(get("/api/admin/auth/profile").header("Authorization", "Bearer " + valid))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/auth/profile").header("Authorization", "Bearer " + invalid))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/profile").header("Authorization", "Bearer " + valid))
                .andExpect(status().isOk());
        assertThat(users.selectById(admin.getId()).getTokenVersion()).isEqualTo(admin.getTokenVersion());
    }
}
