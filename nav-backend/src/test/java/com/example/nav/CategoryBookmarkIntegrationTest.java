package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
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
import org.springframework.test.web.servlet.ResultActions;
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
class CategoryBookmarkIntegrationTest {

    private static final String CATEGORY_URL = "/api/admin/categories";
    private static final String BOOKMARK_URL = "/api/admin/bookmarks";
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
    private CategoryMapper categoryMapper;

    @Autowired
    private BookmarkMapper bookmarkMapper;

    @Test
    void categoryAndBookmarkManagementRequiresAuthentication() throws Exception {
        mockMvc.perform(get(CATEGORY_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(put(BOOKMARK_URL + "/batch-move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1],\"categoryId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void adminCanCrudCategoriesAndBookmarksAndToggleVisibility() throws Exception {
        long categoryId = createCategory("  集成测试分类  ", 710, true);

        mockMvc.perform(put(CATEGORY_URL + "/" + categoryId)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody("更新后的分类", "◎", 720, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(categoryId))
                .andExpect(jsonPath("$.data.name").value("更新后的分类"))
                .andExpect(jsonPath("$.data.sortOrder").value(720));

        mockMvc.perform(put(CATEGORY_URL + "/" + categoryId + "/visible")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false));

        long bookmarkId = createBookmark(categoryId, "  测试书签  ", 30);
        mockMvc.perform(put(BOOKMARK_URL + "/" + bookmarkId)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookmarkBody(categoryId, "更新后的书签", "https://example.com/updated", 40, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(bookmarkId))
                .andExpect(jsonPath("$.data.name").value("更新后的书签"))
                .andExpect(jsonPath("$.data.url").value("https://example.com/updated"))
                .andExpect(jsonPath("$.data.sortOrder").value(40));

        mockMvc.perform(get(BOOKMARK_URL)
                        .header("Authorization", bearerToken())
                        .param("categoryId", Long.toString(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(bookmarkId));

        mockMvc.perform(put(BOOKMARK_URL + "/" + bookmarkId + "/visible")
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false));

        JsonNode categories = adminList(CATEGORY_URL);
        assertThat(findById(categories, categoryId).path("bookmarkCount").asLong()).isEqualTo(1);

        mockMvc.perform(delete(BOOKMARK_URL + "/" + bookmarkId)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk());
        mockMvc.perform(delete(CATEGORY_URL + "/" + categoryId)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isOk());

        assertThat(bookmarkMapper.selectById(bookmarkId)).isNull();
        assertThat(categoryMapper.selectById(categoryId)).isNull();
    }

    @Test
    void deletingCategoryWithBookmarksReturnsConflictWithoutDeletingAnything() throws Exception {
        long categoryId = createCategory("不可删除分类", 730, true);
        long bookmarkId = createBookmark(categoryId, "关联书签", 10);

        mockMvc.perform(delete(CATEGORY_URL + "/" + categoryId)
                        .header("Authorization", bearerToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        assertThat(categoryMapper.selectById(categoryId)).isNotNull();
        assertThat(bookmarkMapper.selectById(bookmarkId)).isNotNull();
    }

    @Test
    void categorySortValidatesWholeRequestBeforeWriting() throws Exception {
        long firstId = createCategory("分类排序甲", 100, true);
        long secondId = createCategory("分类排序乙", 200, true);

        sort(CATEGORY_URL, List.of(
                Map.of("id", firstId, "sortOrder", 900),
                Map.of("id", secondId, "sortOrder", 2)))
                .andExpect(status().isOk());
        assertCategorySort(firstId, 900);
        assertCategorySort(secondId, 2);

        sort(CATEGORY_URL, List.of(
                Map.of("id", firstId, "sortOrder", 111),
                Map.of("id", firstId, "sortOrder", 222)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重复 ID")));
        assertCategorySort(firstId, 900);

        sort(CATEGORY_URL, List.of(
                Map.of("id", firstId, "sortOrder", 333),
                Map.of("id", UNKNOWN_ID, "sortOrder", 1)))
                .andExpect(status().isNotFound());
        assertCategorySort(firstId, 900);

        sort(CATEGORY_URL, List.of(Map.of("id", firstId, "sortOrder", -1)))
                .andExpect(status().isBadRequest());
        assertCategorySort(firstId, 900);
    }

    @Test
    void bookmarkSortValidatesWholeRequestBeforeWriting() throws Exception {
        long categoryId = createCategory("书签排序分类", 740, true);
        long firstId = createBookmark(categoryId, "书签排序甲", 100);
        long secondId = createBookmark(categoryId, "书签排序乙", 200);

        sort(BOOKMARK_URL, List.of(
                Map.of("id", firstId, "sortOrder", 900),
                Map.of("id", secondId, "sortOrder", 2)))
                .andExpect(status().isOk());
        assertBookmarkSort(firstId, 900);
        assertBookmarkSort(secondId, 2);

        sort(BOOKMARK_URL, List.of(
                Map.of("id", firstId, "sortOrder", 111),
                Map.of("id", firstId, "sortOrder", 222)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重复 ID")));
        assertBookmarkSort(firstId, 900);

        sort(BOOKMARK_URL, List.of(
                Map.of("id", firstId, "sortOrder", 333),
                Map.of("id", UNKNOWN_ID, "sortOrder", 1)))
                .andExpect(status().isNotFound());
        assertBookmarkSort(firstId, 900);

        sort(BOOKMARK_URL, List.of(Map.of("id", firstId, "sortOrder", -1)))
                .andExpect(status().isBadRequest());
        assertBookmarkSort(firstId, 900);
    }

    @Test
    void batchMoveAppendsOnlyCrossCategoryBookmarksAndIsIdempotent() throws Exception {
        long sourceCategoryId = createCategory("移动来源分类", 750, true);
        long targetCategoryId = createCategory("移动目标分类", 760, true);
        long existingTargetId = createBookmark(targetCategoryId, "目标原有书签", 40);
        long firstId = createBookmark(sourceCategoryId, "待移动甲", 5);
        long secondId = createBookmark(sourceCategoryId, "待移动乙", 15);
        Bookmark existingTargetBeforeMove = bookmarkMapper.selectById(existingTargetId);
        Bookmark firstBeforeMove = bookmarkMapper.selectById(firstId);
        Bookmark secondBeforeMove = bookmarkMapper.selectById(secondId);

        batchMove(List.of(secondId, existingTargetId, firstId), targetCategoryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.data[0].categoryId").value(targetCategoryId))
                .andExpect(jsonPath("$.data[0].sortOrder").value(50))
                .andExpect(jsonPath("$.data[0].name").value("待移动乙"))
                .andExpect(jsonPath("$.data[0].url").isNotEmpty())
                .andExpect(jsonPath("$.data[0].icon").value("B"))
                .andExpect(jsonPath("$.data[0].description").value("集成测试书签"))
                .andExpect(jsonPath("$.data[0].isRecommend").value(false))
                .andExpect(jsonPath("$.data[0].isExternal").value(true))
                .andExpect(jsonPath("$.data[0].visible").value(true))
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].id").value(existingTargetId))
                .andExpect(jsonPath("$.data[1].sortOrder").value(40))
                .andExpect(jsonPath("$.data[2].id").value(firstId))
                .andExpect(jsonPath("$.data[2].sortOrder").value(60));

        Bookmark firstAfterMove = bookmarkMapper.selectById(firstId);
        Bookmark secondAfterMove = bookmarkMapper.selectById(secondId);
        Bookmark existingTargetAfterMove = bookmarkMapper.selectById(existingTargetId);
        assertBookmarkContentUnchanged(firstBeforeMove, firstAfterMove);
        assertBookmarkContentUnchanged(secondBeforeMove, secondAfterMove);
        assertBookmarkContentUnchanged(existingTargetBeforeMove, existingTargetAfterMove);
        assertThat(firstAfterMove.getUpdatedAt()).isAfter(firstBeforeMove.getUpdatedAt());
        assertThat(secondAfterMove.getUpdatedAt()).isAfter(secondBeforeMove.getUpdatedAt());
        assertThat(existingTargetAfterMove.getUpdatedAt()).isEqualTo(existingTargetBeforeMove.getUpdatedAt());

        JsonNode categoriesAfterMove = adminList(CATEGORY_URL);
        assertThat(findById(categoriesAfterMove, sourceCategoryId).path("bookmarkCount").asLong()).isZero();
        assertThat(findById(categoriesAfterMove, targetCategoryId).path("bookmarkCount").asLong()).isEqualTo(3);

        batchMove(List.of(firstId, secondId), targetCategoryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(firstId))
                .andExpect(jsonPath("$.data[0].sortOrder").value(60))
                .andExpect(jsonPath("$.data[1].id").value(secondId))
                .andExpect(jsonPath("$.data[1].sortOrder").value(50));

        batchMove(List.of(secondId, existingTargetId, firstId), targetCategoryId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.data[0].sortOrder").value(50))
                .andExpect(jsonPath("$.data[1].id").value(existingTargetId))
                .andExpect(jsonPath("$.data[1].sortOrder").value(40))
                .andExpect(jsonPath("$.data[2].id").value(firstId))
                .andExpect(jsonPath("$.data[2].sortOrder").value(60));

        assertBookmarkLocation(firstId, targetCategoryId, 60);
        assertBookmarkLocation(secondId, targetCategoryId, 50);
        assertThat(bookmarkMapper.selectById(firstId).getUpdatedAt()).isEqualTo(firstAfterMove.getUpdatedAt());
        assertThat(bookmarkMapper.selectById(secondId).getUpdatedAt()).isEqualTo(secondAfterMove.getUpdatedAt());

        JsonNode navigationAfterMove = publicNavigation();
        JsonNode sourceAfterMove = findById(navigationAfterMove, sourceCategoryId);
        JsonNode targetAfterMove = findById(navigationAfterMove, targetCategoryId);
        assertThat(sourceAfterMove.path("bookmarks")).isEmpty();
        assertThat(targetAfterMove.path("bookmarks")).hasSize(3);
        assertThat(targetAfterMove.path("bookmarks").get(0).path("id").asLong()).isEqualTo(existingTargetId);
        assertThat(targetAfterMove.path("bookmarks").get(1).path("id").asLong()).isEqualTo(secondId);
        assertThat(targetAfterMove.path("bookmarks").get(2).path("id").asLong()).isEqualTo(firstId);
        assertThat(findById(targetAfterMove.path("bookmarks"), firstId).path("categoryId").asLong())
                .isEqualTo(targetCategoryId);
        assertThat(findById(targetAfterMove.path("bookmarks"), secondId).path("categoryId").asLong())
                .isEqualTo(targetCategoryId);

        sort(BOOKMARK_URL, List.of(
                Map.of("id", firstId, "sortOrder", 45),
                Map.of("id", secondId, "sortOrder", 55)))
                .andExpect(status().isOk());

        JsonNode targetAfterSort = findById(publicNavigation(), targetCategoryId);
        assertThat(targetAfterSort.path("bookmarks")).hasSize(3);
        assertThat(targetAfterSort.path("bookmarks").get(0).path("id").asLong()).isEqualTo(existingTargetId);
        assertThat(targetAfterSort.path("bookmarks").get(1).path("id").asLong()).isEqualTo(firstId);
        assertThat(targetAfterSort.path("bookmarks").get(2).path("id").asLong()).isEqualTo(secondId);
        assertThat(findById(adminList(CATEGORY_URL), targetCategoryId).path("bookmarkCount").asLong()).isEqualTo(3);
    }

    @Test
    void batchMoveRejectsInvalidRequestsWithoutPartialUpdates() throws Exception {
        long sourceCategoryId = createCategory("移动校验来源", 770, true);
        long targetCategoryId = createCategory("移动校验目标", 780, true);
        long firstId = createBookmark(sourceCategoryId, "移动校验甲", 10);
        long secondId = createBookmark(sourceCategoryId, "移动校验乙", 20);

        batchMove(List.of(firstId, firstId), targetCategoryId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重复 ID")));
        assertBookmarkLocation(firstId, sourceCategoryId, 10);

        batchMove(List.of(firstId), UNKNOWN_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("目标分类不存在")));
        assertBookmarkLocation(firstId, sourceCategoryId, 10);

        batchMove(List.of(firstId, UNKNOWN_ID), targetCategoryId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("书签不存在")));
        assertBookmarkLocation(firstId, sourceCategoryId, 10);
        assertBookmarkLocation(secondId, sourceCategoryId, 20);

        batchMove(List.of(-1L), targetCategoryId)
                .andExpect(status().isBadRequest());
        batchMove(List.of(), targetCategoryId)
                .andExpect(status().isBadRequest());
        assertBookmarkLocation(firstId, sourceCategoryId, 10);
    }

    private long createCategory(String name, int sortOrder, boolean visible) throws Exception {
        String response = mockMvc.perform(post(CATEGORY_URL)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(categoryBody(name, "C", sortOrder, visible)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name.trim()))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private long createBookmark(long categoryId, String name, int sortOrder) throws Exception {
        String response = mockMvc.perform(post(BOOKMARK_URL)
                        .header("Authorization", bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookmarkBody(categoryId, name, "https://example.com/" + sortOrder, sortOrder, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(name.trim()))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private String categoryBody(String name, String icon, int sortOrder, boolean visible) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name,
                "icon", icon,
                "sortOrder", sortOrder,
                "visible", visible
        ));
    }

    private String bookmarkBody(
            long categoryId,
            String name,
            String url,
            int sortOrder,
            boolean visible
    ) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("categoryId", categoryId);
        body.put("name", name);
        body.put("url", url);
        body.put("icon", "B");
        body.put("description", "集成测试书签");
        body.put("sortOrder", sortOrder);
        body.put("isRecommend", false);
        body.put("isExternal", true);
        body.put("visible", visible);
        return objectMapper.writeValueAsString(body);
    }

    private ResultActions sort(String baseUrl, List<Map<String, Object>> items) throws Exception {
        return mockMvc.perform(put(baseUrl + "/sort")
                .header("Authorization", bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(items)));
    }

    private ResultActions batchMove(List<Long> ids, long categoryId) throws Exception {
        return mockMvc.perform(put(BOOKMARK_URL + "/batch-move")
                .header("Authorization", bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "ids", ids,
                        "categoryId", categoryId
                ))));
    }

    private JsonNode adminList(String url) throws Exception {
        String response = mockMvc.perform(get(url).header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode publicNavigation() throws Exception {
        String response = mockMvc.perform(get("/api/public/navigation"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode findById(JsonNode items, long id) {
        for (JsonNode item : items) {
            if (item.path("id").asLong() == id) return item;
        }
        throw new AssertionError("没有找到 ID: " + id);
    }

    private void assertCategorySort(long id, int expectedSortOrder) {
        Category category = categoryMapper.selectById(id);
        assertThat(category).isNotNull();
        assertThat(category.getSortOrder()).isEqualTo(expectedSortOrder);
    }

    private void assertBookmarkSort(long id, int expectedSortOrder) {
        Bookmark bookmark = bookmarkMapper.selectById(id);
        assertThat(bookmark).isNotNull();
        assertThat(bookmark.getSortOrder()).isEqualTo(expectedSortOrder);
    }

    private void assertBookmarkLocation(long id, long categoryId, int sortOrder) {
        Bookmark bookmark = bookmarkMapper.selectById(id);
        assertThat(bookmark).isNotNull();
        assertThat(bookmark.getCategoryId()).isEqualTo(categoryId);
        assertThat(bookmark.getSortOrder()).isEqualTo(sortOrder);
    }

    private void assertBookmarkContentUnchanged(Bookmark before, Bookmark after) {
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getName()).isEqualTo(before.getName());
        assertThat(after.getUrl()).isEqualTo(before.getUrl());
        assertThat(after.getIcon()).isEqualTo(before.getIcon());
        assertThat(after.getDescription()).isEqualTo(before.getDescription());
        assertThat(after.getRecommend()).isEqualTo(before.getRecommend());
        assertThat(after.getExternal()).isEqualTo(before.getExternal());
        assertThat(after.getVisible()).isEqualTo(before.getVisible());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    }

    private String bearerToken() {
        User admin = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin")
                .last("LIMIT 1"));
        return "Bearer " + jwtTokenService.createToken(admin);
    }
}
