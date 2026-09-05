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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
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
class CustomLinkIntegrationTest {

    private static final String ADMIN_URL = "/api/admin/custom-links";
    private static final long UNKNOWN_ID = 9_999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void customLinkAdminEndpointsRequireAdminRole() throws Exception {
        mockMvc.perform(get(ADMIN_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get(ADMIN_URL).header("Authorization", bearerToken("user")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanCreateMoveUpdateListAndDeleteCustomLink() throws Exception {
        int previousFooterMax = maxSortOrder(adminLinks(), "footer");
        String createResponse = createLink(
                "  文档入口  ",
                "  /docs?from=header#intro  ",
                " footer ",
                null,
                null
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("文档入口"))
                .andExpect(jsonPath("$.data.url").value("/docs?from=header#intro"))
                .andExpect(jsonPath("$.data.position").value("footer"))
                .andExpect(jsonPath("$.data.sortOrder").value(previousFooterMax + 10))
                .andExpect(jsonPath("$.data.visible").value(true))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(createResponse).path("data").path("id").asLong();
        assertThat(findById(adminLinks(), id).path("title").asText()).isEqualTo("文档入口");

        int previousHeaderMax = maxSortOrder(adminLinks(), "header");
        mockMvc.perform(put(ADMIN_URL + "/" + id)
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody("  文档中心  ", " #documentation ", " header ", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("文档中心"))
                .andExpect(jsonPath("$.data.url").value("#documentation"))
                .andExpect(jsonPath("$.data.position").value("header"))
                .andExpect(jsonPath("$.data.sortOrder").value(previousHeaderMax + 10))
                .andExpect(jsonPath("$.data.visible").value(true));

        mockMvc.perform(delete(ADMIN_URL + "/" + id)
                        .header("Authorization", bearerToken("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(containsId(adminLinks(), id)).isFalse();
    }

    @Test
    void onlySafeUrlsAndSupportedPositionsAreAccepted() throws Exception {
        for (String safeUrl : List.of(
                "https://example.com/path?q=1#part",
                "http://example.com",
                "/admin/settings?tab=site#theme",
                "#navigation")) {
            createLink("安全链接", safeUrl, "header", 500, true)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.url").value(safeUrl));
        }

        for (String unsafeUrl : List.of(
                "javascript:alert(1)",
                "data:text/html,test",
                "file:///etc/passwd",
                "ftp://example.com/file",
                "//evil.example/path",
                "/safe\\evil",
                "https://exa mple.com",
                "https://user@example.com/path",
                "https:///missing-host",
                "#")) {
            createLink("非法链接", unsafeUrl, "header", 500, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }

        for (String invalidPosition : List.of("sidebar", "HEADER", "")) {
            createLink("错误位置", "/", invalidPosition, 500, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    @Test
    void hiddenLinksStayInAdminButPublicListOnlyContainsLegalVisibleLinksInDisplayOrder() throws Exception {
        long hiddenId = createdId(createLink("隐藏链接", "/hidden", "header", 1, true));
        mockMvc.perform(put(ADMIN_URL + "/" + hiddenId + "/visible")
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false));

        createLink("页头靠后", "/header-later", "header", 700, true).andExpect(status().isOk());
        createLink("页脚靠前", "/footer-first", "footer", 1, true).andExpect(status().isOk());

        assertThat(findById(adminLinks(), hiddenId).path("visible").asBoolean()).isFalse();
        JsonNode publicLinks = publicLinks();
        assertThat(containsId(publicLinks, hiddenId)).isFalse();

        boolean footerSeen = false;
        Map<String, Integer> lastSortByPosition = new HashMap<>();
        Map<String, Long> lastIdByPositionAndSort = new HashMap<>();
        for (JsonNode link : publicLinks) {
            String position = link.path("position").asText();
            assertThat(position).isIn("header", "footer");
            assertThat(link.path("visible").asBoolean()).isTrue();
            if ("footer".equals(position)) {
                footerSeen = true;
            } else {
                assertThat(footerSeen).as("header links must precede footer links").isFalse();
            }

            int sortOrder = link.path("sortOrder").asInt();
            int previousSort = lastSortByPosition.getOrDefault(position, Integer.MIN_VALUE);
            assertThat(sortOrder).isGreaterThanOrEqualTo(previousSort);
            if (sortOrder == previousSort) {
                assertThat(link.path("id").asLong())
                        .isGreaterThan(lastIdByPositionAndSort.get(position));
            }
            lastSortByPosition.put(position, sortOrder);
            lastIdByPositionAndSort.put(position, link.path("id").asLong());
        }
    }

    @Test
    void sortingValidatesEveryIdBeforeWritingAndRejectsDuplicates() throws Exception {
        long firstId = createdId(createLink("排序甲", "/sort-a", "header", 100, true));
        long secondId = createdId(createLink("排序乙", "/sort-b", "header", 200, true));

        mockMvc.perform(put(ADMIN_URL + "/sort")
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                Map.of("id", firstId, "sortOrder", 900),
                                Map.of("id", secondId, "sortOrder", 2)))))
                .andExpect(status().isOk());

        assertThat(findById(adminLinks(), firstId).path("sortOrder").asInt()).isEqualTo(900);
        assertThat(findById(adminLinks(), secondId).path("sortOrder").asInt()).isEqualTo(2);

        mockMvc.perform(put(ADMIN_URL + "/sort")
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                Map.of("id", firstId, "sortOrder", 111),
                                Map.of("id", firstId, "sortOrder", 222)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertThat(findById(adminLinks(), firstId).path("sortOrder").asInt()).isEqualTo(900);

        mockMvc.perform(put(ADMIN_URL + "/sort")
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                Map.of("id", firstId, "sortOrder", 333),
                                Map.of("id", UNKNOWN_ID, "sortOrder", 1)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        assertThat(findById(adminLinks(), firstId).path("sortOrder").asInt()).isEqualTo(900);
    }

    @Test
    void nullSortItemsAreBadRequestsWithoutChangingAnyLink() throws Exception {
        long id = createdId(createLink("空值排序回归", "/null-sort", "header", 100, true));
        JsonNode before = adminLinks();
        for (String body : List.of("[null]", "[{\"id\":" + id + ",\"sortOrder\":900},null]")) {
            mockMvc.perform(put(ADMIN_URL + "/sort")
                            .header("Authorization", bearerToken("admin"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
            assertThat(adminLinks()).isEqualTo(before);
        }
    }

    @Test
    void unknownLinksReturnNotFoundForEveryMutation() throws Exception {
        mockMvc.perform(put(ADMIN_URL + "/" + UNKNOWN_ID)
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody("不存在", "/missing", "header", 1, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(put(ADMIN_URL + "/" + UNKNOWN_ID + "/visible")
                        .header("Authorization", bearerToken("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete(ADMIN_URL + "/" + UNKNOWN_ID)
                        .header("Authorization", bearerToken("admin")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private ResultActions createLink(
            String title,
            String url,
            String position,
            Integer sortOrder,
            Boolean visible
    ) throws Exception {
        return mockMvc.perform(post(ADMIN_URL)
                .header("Authorization", bearerToken("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(linkBody(title, url, position, sortOrder, visible)));
    }

    private long createdId(ResultActions actions) throws Exception {
        String response = actions.andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private String linkBody(
            String title,
            String url,
            String position,
            Integer sortOrder,
            Boolean visible
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("url", url);
        body.put("position", position);
        if (sortOrder != null) body.put("sortOrder", sortOrder);
        if (visible != null) body.put("visible", visible);
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode adminLinks() throws Exception {
        String response = mockMvc.perform(get(ADMIN_URL)
                        .header("Authorization", bearerToken("admin")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode publicLinks() throws Exception {
        String response = mockMvc.perform(get("/api/public/custom-links"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode findById(JsonNode links, long id) {
        for (JsonNode link : links) {
            if (link.path("id").asLong() == id) return link;
        }
        throw new AssertionError("没有找到自定义链接 ID: " + id);
    }

    private boolean containsId(JsonNode links, long id) {
        for (JsonNode link : links) {
            if (link.path("id").asLong() == id) return true;
        }
        return false;
    }

    private int maxSortOrder(JsonNode links, String position) {
        int max = -10;
        for (JsonNode link : links) {
            if (position.equals(link.path("position").asText())) {
                max = Math.max(max, link.path("sortOrder").asInt());
            }
        }
        return max;
    }

    private String bearerToken(String role) {
        if (!"admin".equals(role)) {
            LocalDateTime now = LocalDateTime.now();
            User viewer = new User();
            viewer.setUsername("custom-link-viewer");
            viewer.setPassword(passwordEncoder.encode("Viewer!Pass2026"));
            viewer.setNickname("Viewer");
            viewer.setRole("user");
            viewer.setStatus(true);
            viewer.setTokenVersion(0);
            viewer.setCreatedAt(now);
            viewer.setUpdatedAt(now);
            userMapper.insert(viewer);
            return "Bearer " + jwtTokenService.createToken(viewer);
        }

        User admin = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin")
                .last("LIMIT 1"));
        return "Bearer " + jwtTokenService.createToken(admin);
    }
}
