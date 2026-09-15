package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.category.dto.CategoryCreateDTO;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.customlink.dto.CustomLinkDTO;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用原始 JSON 保留浮点/指数 token，经过真实 MVC 绑定后验证业务数据及缓存版本。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:numeric_input_regressions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
class NumericInputIntegrationTest extends ConcurrentDatabaseTestSupport {

    @Autowired CategoryService categories;
    @Autowired BookmarkService bookmarks;
    @Autowired SearchEngineService searches;
    @Autowired CustomLinkService links;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JwtTokenService tokens;

    private static final List<String> INVALID_INTEGERS = List.of(
            "0.5", "-0.5", "1.0", "1e0", "-1e-1", "1e309",
            "\"0.5\"", "\"-0.5\"", "\"1.0\"", "\"1e0\"",
            "2147483648", "-2147483649", "\"2147483648\"");

    enum Kind {
        CATEGORY("nav_category", "categories"), BOOKMARK("nav_bookmark", "bookmarks"),
        SEARCH("search_engine", "search-engines"), LINK("custom_link", "custom-links");
        final String table;
        final String path;
        Kind(String table, String path) { this.table = table; this.path = path; }
        String endpoint() { return "/api/admin/" + path; }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void createAndPartialUpdateRejectNonIntegerSortOrderBeforeAnyWrite(Kind kind) throws Exception {
        long category = category();
        long id = create(kind, category);
        var before = snapshot();
        for (String token : INVALID_INTEGERS) {
            reject(post(kind.endpoint()), createBody(kind, category, token), before);
            reject(put(kind.endpoint() + "/" + id), "{\"sortOrder\":" + token + "}", before);
        }
        // 同一连接后续合法请求必须仍能成功，错误输入不能污染解析器或事务状态。
        mvc.perform(auth(put(kind.endpoint() + "/" + id)).content("{\"sortOrder\":17}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sortOrder").value(17));
        assertThat(sortOrder(kind, id)).isEqualTo(17);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void nestedSortIdsAndOrdersAreValidatedBeforeTheFirstListItemWrites(Kind kind) throws Exception {
        long category = category();
        long first = create(kind, category);
        long second = create(kind, category);
        var before = snapshot();
        String validFirst = "{\"id\":" + first + ",\"sortOrder\":222}";
        for (String token : invalidLongs(second)) {
            reject(put(kind.endpoint() + "/sort"), "[" + validFirst
                    + ",{\"id\":" + token + ",\"sortOrder\":333}]", before);
        }
        for (String token : INVALID_INTEGERS) {
            reject(put(kind.endpoint() + "/sort"), "[" + validFirst
                    + ",{\"id\":" + second + ",\"sortOrder\":" + token + "}]", before);
        }
        mvc.perform(auth(put(kind.endpoint() + "/sort")).content("[" + validFirst
                        + ",{\"id\":\"" + second + "\",\"sortOrder\":\"333\"}]"))
                .andExpect(status().isOk());
        assertThat(sortOrder(kind, first)).isEqualTo(222);
        assertThat(sortOrder(kind, second)).isEqualTo(333);
    }

    @Test
    void bookmarkCategoryAndPrimitiveIdListNeverTruncateToExistingRows() throws Exception {
        long source = category();
        long target = category();
        long first = create(Kind.BOOKMARK, source);
        long second = create(Kind.BOOKMARK, source);
        var before = snapshot();
        for (String token : invalidLongs(target)) {
            reject(post(Kind.BOOKMARK.endpoint()), "{\"categoryId\":" + token
                    + ",\"name\":\"不得创建\",\"url\":\"https://example.com/\"}", before);
            reject(put(Kind.BOOKMARK.endpoint() + "/" + first),
                    "{\"categoryId\":" + token + "}", before);
            reject(put(Kind.BOOKMARK.endpoint() + "/batch-move"),
                    "{\"ids\":[" + first + "],\"categoryId\":" + token + "}", before);
        }
        for (String token : invalidLongs(second)) {
            reject(put(Kind.BOOKMARK.endpoint() + "/batch-move"),
                    "{\"ids\":[" + first + "," + token + "],\"categoryId\":" + target + "}", before);
        }
        mvc.perform(auth(put(Kind.BOOKMARK.endpoint() + "/batch-move"))
                        .content("{\"ids\":[\"" + first + "\",\"" + second
                                + "\"],\"categoryId\":\"" + target + "\"}"))
                .andExpect(status().isOk());
        assertThat(bookmarkCategory(first)).isEqualTo(target);
        assertThat(bookmarkCategory(second)).isEqualTo(target);
    }

    @Test
    void expectedVersionCannotBeTruncatedIntoTheCurrentVersion() throws Exception {
        int current = version();
        var before = snapshot();
        for (String token : List.of(current + ".5", current + ".0", current + "e0", "-0.5",
                "\"" + current + ".5\"", "\"" + current + "e0\"", "2147483648", "1e309")) {
            reject(put("/api/admin/site-config"), "{\"siteName\":\"不得提交\",\"expectedVersion\":"
                    + token + "}", before);
        }
        mvc.perform(auth(put("/api/admin/site-config"))
                        .content("{\"siteName\":\"合法重试\",\"expectedVersion\":\"" + current + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.siteName").value("合法重试"));
        assertThat(version()).isEqualTo(current + 1);
        var committed = snapshot();
        mvc.perform(auth(put("/api/admin/site-config"))
                        .content("{\"siteName\":\"过期修改\",\"expectedVersion\":" + current + "}"))
                .andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(committed);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void pathIdsRejectFractionsExponentsAndOverflowWithoutChangingTheTruncatedTarget(Kind kind) throws Exception {
        long id = create(kind, category());
        var before = snapshot();
        for (String pathId : List.of(id + ".5", id + ".0", id + "e0", "-0.5", "9223372036854775808")) {
            reject(put(kind.endpoint() + "/" + pathId), "{\"sortOrder\":555}", before);
            reject(put(kind.endpoint() + "/" + pathId + "/visible"), "{\"visible\":false}", before);
        }
        mvc.perform(auth(put(kind.endpoint() + "/" + id)).content("{\"sortOrder\":555}"))
                .andExpect(status().isOk());
        assertThat(sortOrder(kind, id)).isEqualTo(555);
    }

    @Test
    void queryIdsRejectFractionsButAcceptExactLongBounds() throws Exception {
        long category = category();
        create(Kind.BOOKMARK, category);
        var before = snapshot();
        for (String value : List.of(category + ".5", category + ".0", category + "e0", "-0.5",
                "9223372036854775808", "-9223372036854775809")) {
            mvc.perform(auth(get(Kind.BOOKMARK.endpoint())).queryParam("categoryId", value))
                    .andExpect(status().isBadRequest());
            assertThat(snapshot()).as("query categoryId=%s", value).isEqualTo(before);
        }
        mvc.perform(auth(get(Kind.BOOKMARK.endpoint())).queryParam("categoryId", Long.toString(category)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        for (String bound : List.of(Long.toString(Long.MAX_VALUE), Long.toString(Long.MIN_VALUE))) {
            mvc.perform(auth(get(Kind.BOOKMARK.endpoint())).queryParam("categoryId", bound))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void integerAndQuotedIntegerBoundariesRemainExact(Kind kind) throws Exception {
        long id = create(kind, category());
        for (String value : List.of("0", "\"0\"", "2147483647", "\"2147483647\"")) {
            mvc.perform(auth(put(kind.endpoint() + "/" + id)).content("{\"sortOrder\":" + value + "}"))
                    .andExpect(status().isOk());
            assertThat(sortOrder(kind, id)).isEqualTo(Integer.parseInt(value.replace("\"", "")));
        }
        var before = snapshot();
        for (String longBound : List.of("9223372036854775807", "\"9223372036854775807\"")) {
            // Long 最大值合法绑定后由业务返回不存在；溢出则应在绑定阶段返回 400。
            mvc.perform(auth(put(kind.endpoint() + "/sort")).content("[{\"id\":" + longBound
                            + ",\"sortOrder\":1}]"))
                    .andExpect(status().isNotFound());
            assertThat(snapshot()).isEqualTo(before);
        }
        mvc.perform(auth(put(kind.endpoint() + "/" + Long.MAX_VALUE)).content("{\"sortOrder\":1}"))
                .andExpect(status().isNotFound());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void invalidAndValidRequestsInEitherOrderPreserveCommittedStateAndAllowRecovery(boolean invalidFirst) throws Exception {
        long id = category();
        try {
            var initial = snapshot();
            String endpoint = Kind.CATEGORY.endpoint() + "/" + id;
            if (invalidFirst) reject(put(endpoint), "{\"sortOrder\":-0.5}", initial);
            mvc.perform(auth(put(endpoint)).content("{\"sortOrder\":123}"))
                    .andExpect(status().isOk());
            var committed = snapshot();
            assertThat(sortOrder(Kind.CATEGORY, id)).isEqualTo(123);
            if (!invalidFirst) reject(put(endpoint), "{\"sortOrder\":123.5}", committed);
            mvc.perform(auth(put(endpoint)).content("{\"sortOrder\":\"456\"}"))
                    .andExpect(status().isOk());
            assertThat(sortOrder(Kind.CATEGORY, id)).isEqualTo(456);
            assertThat(version()).isEqualTo(((Number) initial.get("site_config").get(0).get("version")).intValue() + 2);
        } finally {
            categories.delete(id);
        }
    }

    private List<String> invalidLongs(long existingId) {
        return List.of(existingId + ".5", existingId + ".0", existingId + "e0", "-0.5", "1e309",
                "\"" + existingId + ".5\"", "\"" + existingId + "e0\"",
                "9223372036854775808", "-9223372036854775809", "\"9223372036854775808\"");
    }

    private String createBody(Kind kind, long category, String sort) {
        return switch (kind) {
            case CATEGORY -> "{\"name\":\"不得创建\",\"sortOrder\":" + sort + "}";
            case BOOKMARK -> "{\"categoryId\":" + category
                    + ",\"name\":\"不得创建\",\"url\":\"https://example.com/\",\"sortOrder\":" + sort + "}";
            case SEARCH -> "{\"name\":\"不得创建\",\"searchUrl\":\"https://example.com/\",\"sortOrder\":" + sort + "}";
            case LINK -> "{\"title\":\"不得创建\",\"url\":\"/\",\"position\":\"header\",\"sortOrder\":" + sort + "}";
        };
    }

    private long category() {
        return categories.create(new CategoryCreateDTO("数值测试分类", "", 10, true)).id();
    }

    private long create(Kind kind, long category) {
        return switch (kind) {
            case CATEGORY -> category();
            case BOOKMARK -> bookmarks.create(new BookmarkCreateDTO(category, "数值书签", "https://example.com/",
                    "", "", 10, false, true, true)).id();
            case SEARCH -> searches.create(new SearchEngineDTO("数值搜索", "", "https://example.com/", "", 10, true)).id();
            case LINK -> links.create(new CustomLinkDTO("数值链接", "/", "header", 10, true)).id();
        };
    }

    private void reject(MockHttpServletRequestBuilder request, String body,
                        Map<String, List<Map<String, Object>>> before) throws Exception {
        mvc.perform(auth(request).content(body)).andExpect(status().isBadRequest());
        assertThat(snapshot()).as("非法数值请求不能产生部分写入：%s", body).isEqualTo(before);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        User admin = users.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, "admin").last("LIMIT 1"));
        return request.header("Authorization", "Bearer " + tokens.createToken(admin)).contentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("site_config", "nav_category", "nav_bookmark", "search_engine", "custom_link")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }

    private int sortOrder(Kind kind, long id) {
        return jdbc.queryForObject("SELECT sort_order FROM " + kind.table + " WHERE id=?", Integer.class, id);
    }

    private long bookmarkCategory(long id) {
        return jdbc.queryForObject("SELECT category_id FROM nav_bookmark WHERE id=?", Long.class, id);
    }

    private int version() { return jdbc.queryForObject("SELECT version FROM site_config WHERE id=1", Integer.class); }
}
