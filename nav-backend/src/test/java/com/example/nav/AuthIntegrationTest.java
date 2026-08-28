package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.config.JwtProperties;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

    private static final String LOGIN_URL = "/api/admin/auth/login";
    private static final String PROFILE_URL = "/api/admin/auth/profile";
    private static final String PASSWORD_URL = "/api/admin/auth/password";
    private static final String LOGOUT_ALL_URL = "/api/admin/auth/logout-all";
    private static final String INITIAL_PASSWORD = "Local!Start2026";
    private static final String STRONG_PASSWORD = "Cedar!River2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void adminEndpointsRequireAuthenticationAndLoginReturnsUsableJwt() throws Exception {
        mockMvc.perform(get("/api/admin/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        String token = login("admin", INITIAL_PASSWORD);
        expectProfileUsable(token, "admin", "admin");
    }

    @Test
    void validNonAdminJwtCannotAccessAdminEndpoints() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername("viewer");
        user.setPassword(passwordEncoder.encode("Viewer!Pass2026"));
        user.setNickname("Viewer");
        user.setRole("user");
        user.setStatus(true);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        mockMvc.perform(get("/api/admin/categories")
                        .header("Authorization", bearer(jwtTokenService.createToken(user))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void passwordChangeRevokesOldTokenAndReplacesLoginPassword() throws Exception {
        String oldToken = login("admin", INITIAL_PASSWORD);
        String parallelOldToken = login("admin", INITIAL_PASSWORD);

        changePassword(oldToken, INITIAL_PASSWORD, STRONG_PASSWORD, STRONG_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        expectTokenRejected(oldToken);
        expectTokenRejected(parallelOldToken);
        loginRejected("admin", INITIAL_PASSWORD);

        String newToken = login("admin", STRONG_PASSWORD);
        expectProfileUsable(newToken, "admin", "admin");
    }

    @Test
    void passwordChangeRejectsInvalidCurrentWeakMismatchReuseAndPolicyViolations() throws Exception {
        String token = login("admin", INITIAL_PASSWORD);

        expectPasswordRejected(token, "wrong-password", STRONG_PASSWORD, STRONG_PASSWORD, "当前密码错误");
        expectPasswordRejected(token, INITIAL_PASSWORD, "onlylowercase12", "onlylowercase12", "至少包含");
        expectPasswordRejected(token, INITIAL_PASSWORD, STRONG_PASSWORD, "Different!Pass2026", "不一致");
        expectPasswordRejected(token, INITIAL_PASSWORD, INITIAL_PASSWORD, INITIAL_PASSWORD, "不能与当前密码相同");
        expectPasswordRejected(token, INITIAL_PASSWORD, "Secure Admin!2026", "Secure Admin!2026", "不能包含空白字符");
        expectPasswordRejected(token, INITIAL_PASSWORD, "Safe\uFEFFPass123!", "Safe\uFEFFPass123!", "不能包含空白字符");
        expectPasswordRejected(token, INITIAL_PASSWORD, "SecureAdmin!2026", "SecureAdmin!2026", "包含用户名");

        String tooFewUnicodeCodePoints = "Aa" + "😀".repeat(5);
        expectPasswordRejected(
                token,
                INITIAL_PASSWORD,
                tooFewUnicodeCodePoints,
                tooFewUnicodeCodePoints,
                "长度必须为 12-72 个字符"
        );

        String overBcryptByteLimit = "Aa1!" + "密".repeat(23);
        expectPasswordRejected(
                token,
                INITIAL_PASSWORD,
                overBcryptByteLimit,
                overBcryptByteLimit,
                "不能超过 72 字节"
        );
        expectProfileUsable(token, "admin", "admin");
    }

    @Test
    void passwordPolicyAcceptsUnicodeUppercaseLowercaseAndDecimalClasses() throws Exception {
        String token = login("admin", INITIAL_PASSWORD);
        String unicodePassword = "ÉÉÉééé123456";

        changePassword(token, INITIAL_PASSWORD, unicodePassword, unicodePassword)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        expectTokenRejected(token);
        expectProfileUsable(login("admin", unicodePassword), "admin", "admin");
    }

    @Test
    void passwordUpdateUsesTokenVersionAsAnAtomicOptimisticGuard() {
        User admin = adminUser();
        int currentVersion = admin.getTokenVersion() == null ? 0 : admin.getTokenVersion();

        int updated = userMapper.updatePasswordAndIncrementTokenVersion(
                admin.getId(),
                currentVersion + 1,
                admin.getPassword(),
                passwordEncoder.encode(STRONG_PASSWORD),
                LocalDateTime.now()
        );

        User unchanged = userMapper.selectById(admin.getId());
        assertEquals(0, updated);
        assertEquals(admin.getPassword(), unchanged.getPassword());
        assertEquals(currentVersion, unchanged.getTokenVersion());
    }

    @Test
    void logoutAllRevokesExistingTokenAndFreshLoginGetsUsableToken() throws Exception {
        String oldToken = login("admin", INITIAL_PASSWORD);
        String parallelOldToken = login("admin", INITIAL_PASSWORD);

        mockMvc.perform(post(LOGOUT_ALL_URL).header("Authorization", bearer(oldToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        expectTokenRejected(oldToken);
        expectTokenRejected(parallelOldToken);
        String newToken = login("admin", INITIAL_PASSWORD);
        expectProfileUsable(newToken, "admin", "admin");
    }

    @Test
    void legacyVersionZeroTokenWorksButForgedOrStaleTokensAreRejected() throws Exception {
        User admin = adminUser();

        String legacyToken = createLegacyToken(admin);
        expectProfileUsable(legacyToken, "admin", "admin");

        User staleAdmin = tokenUser(admin, admin.getId(), -1);
        expectTokenRejected(jwtTokenService.createToken(staleAdmin));

        User wrongIdentity = tokenUser(admin, 9_999_999L, 0);
        expectTokenRejected(jwtTokenService.createToken(wrongIdentity));

        expectTokenRejected(tamperSignature(jwtTokenService.createToken(admin)));

        mockMvc.perform(post(LOGOUT_ALL_URL).header("Authorization", bearer(legacyToken)))
                .andExpect(status().isOk());
        expectTokenRejected(legacyToken);
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(
            String token,
            String currentPassword,
            String newPassword,
            String confirmPassword
    ) throws Exception {
        return mockMvc.perform(put(PASSWORD_URL)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "currentPassword", currentPassword,
                        "newPassword", newPassword,
                        "confirmPassword", confirmPassword
                ))));
    }

    private void expectPasswordRejected(
            String token,
            String currentPassword,
            String newPassword,
            String confirmPassword,
            String messageFragment
    ) throws Exception {
        changePassword(token, currentPassword, newPassword, confirmPassword)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messageFragment)));
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        return root.path("data").path("token").asText();
    }

    private void loginRejected(String username, String password) throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private void expectProfileUsable(String token, String username, String role) throws Exception {
        mockMvc.perform(get(PROFILE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.role").value(role));
    }

    private void expectTokenRejected(String token) throws Exception {
        mockMvc.perform(get(PROFILE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    private User adminUser() {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin")
                .last("LIMIT 1"));
    }

    private User tokenUser(User source, Long id, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setUsername(source.getUsername());
        user.setRole(source.getRole());
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private String createLegacyToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(jwtProperties.getExpirationMinutes(), ChronoUnit.MINUTES)))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private String tamperSignature(String token) {
        String[] parts = token.split("\\.");
        char replacement = parts[2].charAt(0) == 'a' ? 'b' : 'a';
        parts[2] = replacement + parts[2].substring(1);
        return String.join(".", parts);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
