package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchEngineIntegrationTest {

    private static final String ADMIN_URL = "/api/admin/search-engines";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserMapper userMapper;

    @Test
    void searchEngineAdminEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get(ADMIN_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void adminCanCreateUpdateListAndDeleteSearchEngine() throws Exception {
        String createResponse = mockMvc.perform(post(ADMIN_URL)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(engineBody(
                                "  DuckDuckGo  ",
                                "https://duckduckgo.com/?q={keyword}",
                                "隐私搜索",
                                45,
                                true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("DuckDuckGo"))
                .andExpect(jsonPath("$.data.searchUrl").value("https://duckduckgo.com/?q={keyword}"))
                .andExpect(jsonPath("$.data.visible").value(true))
                .andExpect(jsonPath("$.data.isDefault").value(false))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(createResponse).path("data").path("id").asLong();
        assertThat(findById(adminEngines(), id).path("name").asText()).isEqualTo("DuckDuckGo");

        mockMvc.perform(put(ADMIN_URL + "/" + id)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(engineBody(
                                "DuckDuckGo Lite",
                                "https://lite.duckduckgo.com/lite/?q={keyword}",
                                "轻量隐私搜索",
                                46,
                                false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("DuckDuckGo Lite"))
                .andExpect(jsonPath("$.data.sortOrder").value(46))
                .andExpect(jsonPath("$.data.visible").value(false));

        assertThat(containsId(publicEngines(), id)).isFalse();

        mockMvc.perform(delete(ADMIN_URL + "/" + id)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(containsId(adminEngines(), id)).isFalse();
    }

    @Test
    void settingAndHidingDefaultKeepsExactlyOneVisibleDefault() throws Exception {
        JsonNode initial = adminEngines();
        long oldDefaultId = findDefault(initial).path("id").asLong();
        long targetId = findDifferentId(initial, oldDefaultId);

        setVisible(targetId, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false));

        mockMvc.perform(put(ADMIN_URL + "/" + targetId + "/default")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(targetId))
                .andExpect(jsonPath("$.data.isDefault").value(true))
                .andExpect(jsonPath("$.data.visible").value(true));

        JsonNode afterSetDefault = adminEngines();
        assertThat(findById(afterSetDefault, oldDefaultId).path("isDefault").asBoolean()).isFalse();
        assertExactlyOneVisibleDefault(afterSetDefault);
        assertThat(publicEngines().get(0).path("id").asLong()).isEqualTo(targetId);

        JsonNode target = findById(afterSetDefault, targetId);
        mockMvc.perform(put(ADMIN_URL + "/" + targetId)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(engineBody(
                                target.path("name").asText(),
                                target.path("searchUrl").asText(),
                                target.path("placeholder").asText(),
                                target.path("sortOrder").asInt(),
                                false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(jsonPath("$.data.isDefault").value(false));

        JsonNode afterHide = adminEngines();
        assertExactlyOneVisibleDefault(afterHide);
        assertThat(containsId(publicEngines(), targetId)).isFalse();
    }

    @Test
    void lastVisibleEngineCannotBeHiddenOrDeleted() throws Exception {
        JsonNode engines = adminEngines();
        long defaultId = findDefault(engines).path("id").asLong();
        for (JsonNode engine : engines) {
            if (engine.path("id").asLong() != defaultId && engine.path("visible").asBoolean()) {
                setVisible(engine.path("id").asLong(), false).andExpect(status().isOk());
            }
        }

        setVisible(defaultId, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        mockMvc.perform(delete(ADMIN_URL + "/" + defaultId)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        JsonNode publicEngines = publicEngines();
        assertThat(publicEngines).hasSize(1);
        assertThat(publicEngines.get(0).path("id").asLong()).isEqualTo(defaultId);
        assertThat(publicEngines.get(0).path("isDefault").asBoolean()).isTrue();
    }

    @Test
    void deletingDefaultPromotesAnotherVisibleEngine() throws Exception {
        JsonNode initial = adminEngines();
        long oldDefaultId = findDefault(initial).path("id").asLong();
        long targetId = findDifferentId(initial, oldDefaultId);

        mockMvc.perform(put(ADMIN_URL + "/" + targetId + "/default")
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk());

        mockMvc.perform(delete(ADMIN_URL + "/" + targetId)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk());

        JsonNode remaining = adminEngines();
        assertThat(containsId(remaining, targetId)).isFalse();
        assertExactlyOneVisibleDefault(remaining);
    }

    @Test
    void unsafeSearchTemplatesAreRejected() throws Exception {
        for (String template : List.of(
                "javascript:alert({keyword})",
                "https://{keyword}.example.com/search",
                "https://example.com/search?q={other}",
                "https://user@example.com/search?q={keyword}",
                "https://example.com/search#q={keyword}",
                "https:///search?q={keyword}")) {
            mockMvc.perform(post(ADMIN_URL)
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(engineBody("非法模板", template, "", 90, true)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void sortingWorksAndDuplicateIdsAreRejectedWithoutPartialUpdates() throws Exception {
        JsonNode initial = adminEngines();
        long firstId = initial.get(0).path("id").asLong();
        long secondId = initial.get(1).path("id").asLong();

        mockMvc.perform(put(ADMIN_URL + "/sort")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                Map.of("id", firstId, "sortOrder", 900),
                                Map.of("id", secondId, "sortOrder", 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(secondId));

        int committedSortOrder = findById(adminEngines(), firstId).path("sortOrder").asInt();
        mockMvc.perform(put(ADMIN_URL + "/sort")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                Map.of("id", firstId, "sortOrder", 111),
                                Map.of("id", firstId, "sortOrder", 222)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(findById(adminEngines(), firstId).path("sortOrder").asInt())
                .isEqualTo(committedSortOrder);
    }

    @Test
    void nullSortItemsAreBadRequestsWithoutChangingAnyEngine() throws Exception {
        JsonNode before = adminEngines();
        long id = before.get(0).path("id").asLong();
        for (String body : List.of("[null]", "[{\"id\":" + id + ",\"sortOrder\":900},null]")) {
            mockMvc.perform(put(ADMIN_URL + "/sort")
                            .header("Authorization", bearerToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
            assertThat(adminEngines()).isEqualTo(before);
        }
    }

    private org.springframework.test.web.servlet.ResultActions setVisible(long id, boolean visible) throws Exception {
        return mockMvc.perform(put(ADMIN_URL + "/" + id + "/visible")
                .header("Authorization", bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visible\":" + visible + "}"));
    }

    private JsonNode adminEngines() throws Exception {
        String response = mockMvc.perform(get(ADMIN_URL)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode publicEngines() throws Exception {
        String response = mockMvc.perform(get("/api/public/search-engines"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private String engineBody(
            String name,
            String searchUrl,
            String placeholder,
            int sortOrder,
            boolean visible
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("icon", "S");
        body.put("searchUrl", searchUrl);
        body.put("placeholder", placeholder);
        body.put("sortOrder", sortOrder);
        body.put("visible", visible);
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode findDefault(JsonNode engines) {
        for (JsonNode engine : engines) {
            if (engine.path("isDefault").asBoolean()) return engine;
        }
        throw new AssertionError("没有找到默认搜索引擎");
    }

    private JsonNode findById(JsonNode engines, long id) {
        for (JsonNode engine : engines) {
            if (engine.path("id").asLong() == id) return engine;
        }
        throw new AssertionError("没有找到搜索引擎 ID: " + id);
    }

    private long findDifferentId(JsonNode engines, long excludedId) {
        for (JsonNode engine : engines) {
            if (engine.path("id").asLong() != excludedId) return engine.path("id").asLong();
        }
        throw new AssertionError("没有可用的其他搜索引擎");
    }

    private boolean containsId(JsonNode engines, long id) {
        for (JsonNode engine : engines) {
            if (engine.path("id").asLong() == id) return true;
        }
        return false;
    }

    private void assertExactlyOneVisibleDefault(JsonNode engines) {
        int defaultCount = 0;
        for (JsonNode engine : engines) {
            if (engine.path("isDefault").asBoolean()) {
                assertThat(engine.path("visible").asBoolean()).isTrue();
                defaultCount++;
            }
        }
        assertThat(defaultCount).isEqualTo(1);
    }

    private String bearerToken() {
        User admin = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin")
                .last("LIMIT 1"));
        return "Bearer " + jwtTokenService.createToken(admin);
    }
}
