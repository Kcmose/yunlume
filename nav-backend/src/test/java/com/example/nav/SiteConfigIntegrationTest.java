package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SiteConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SiteConfigMapper siteConfigMapper;

    @Test
    void adminCanConfigureSeparateDesktopAndMobileBackgroundImages() throws Exception {
        String token = adminToken();
        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "backgroundType": "image",
                                  "backgroundImage": "https://example.com/desktop.jpg",
                                  "mobileBackgroundImage": "/uploads/backgrounds/mobile.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backgroundImage").value("https://example.com/desktop.jpg"))
                .andExpect(jsonPath("$.data.mobileBackgroundImage").value("/uploads/backgrounds/mobile.png"));

        mockMvc.perform(get("/api/public/site-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backgroundImage").value("https://example.com/desktop.jpg"))
                .andExpect(jsonPath("$.data.mobileBackgroundImage").value("/uploads/backgrounds/mobile.png"));
    }

    @Test
    void backgroundImageRejectsUnsafeSchemesAndProtocolRelativeUrls() throws Exception {
        for (String requestBody : new String[]{
                "{\"expectedVersion\":0,\"backgroundImage\":\"javascript:alert(1)\"}",
                "{\"expectedVersion\":0,\"backgroundImage\":\"//cdn.example.com/background.jpg\"}",
                "{\"expectedVersion\":0,\"mobileBackgroundImage\":\"//cdn.example.com/mobile.jpg\"}"
        }) {
            mockMvc.perform(put("/api/admin/site-config")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void publicSiteConfigIgnoresInvalidBearerAndReturnsPersistedConfig() throws Exception {
        String expectedSiteName = "Persisted public configuration";
        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "siteName": "Persisted public configuration"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value(expectedSiteName));

        mockMvc.perform(get("/api/public/site-config")
                        .header("Authorization", "Bearer definitely.invalid.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value(expectedSiteName));
    }

    @Test
    void updateRequiresVersionAndIncrementsItAtomically() throws Exception {
        mockMvc.perform(get("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"siteName\":\"  Atomic site  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value("Atomic site"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"siteDescription\":\"stale overwrite\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("站点配置已被其他会话修改，请刷新后重试"));

        mockMvc.perform(get("/api/public/site-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value("Atomic site"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.siteDescription")
                        .value(org.hamcrest.Matchers.not("stale overwrite")));
    }

    @Test
    void updateRejectsMissingVersionBlankNameAndImageModeWithoutDesktopImage() throws Exception {
        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteName\":\"No version\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"siteName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("站点名称不能为空"));

        mockMvc.perform(put("/api/admin/site-config")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"backgroundType\":\"image\",\"backgroundImage\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("图片背景模式必须配置 PC 背景图片"));
    }

    @Test
    void adminSiteConfigStillRejectsInvalidBearer() throws Exception {
        mockMvc.perform(get("/api/admin/site-config")
                        .header("Authorization", "Bearer definitely.invalid.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingSiteConfigFailsClosedWithoutRecreatingDemoData() throws Exception {
        siteConfigMapper.delete(null);

        mockMvc.perform(get("/api/public/site-config"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message")
                        .value("站点配置不存在，请管理员检查数据库初始化或备份恢复状态"));

        org.junit.jupiter.api.Assertions.assertEquals(0, siteConfigMapper.selectCount(null));
    }

    private String adminToken() {
        User admin = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin")
                .last("LIMIT 1"));
        return jwtTokenService.createToken(admin);
    }
}
