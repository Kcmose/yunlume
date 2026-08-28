package com.example.nav;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PublicNavigationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicEndpointsExposeSeededDarkThemeAndNavigation() throws Exception {
        mockMvc.perform(get("/api/public/site-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteName").value("yunlume"))
                .andExpect(jsonPath("$.data.backgroundColor").value("#050505"))
                .andExpect(jsonPath("$.data.backgroundImage").value(""))
                .andExpect(jsonPath("$.data.mobileBackgroundImage").value(""))
                .andExpect(jsonPath("$.data.fontColor").value("#ffffff"));

        mockMvc.perform(get("/api/public/navigation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].visible").value(true))
                .andExpect(jsonPath("$.data[0].bookmarks").isArray())
                .andExpect(jsonPath("$.data[0].bookmarks.length()").isNumber());

        mockMvc.perform(get("/api/public/search-engines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].isDefault").value(true));

        mockMvc.perform(get("/api/public/custom-links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
    }
}
