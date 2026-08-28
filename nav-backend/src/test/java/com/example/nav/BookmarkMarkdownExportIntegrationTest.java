package com.example.nav;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.security.JwtTokenService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookmarkMarkdownExportIntegrationTest {

    private static final String EXPORT_URL = "/api/admin/data/bookmarks/markdown";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private BookmarkMapper bookmarkMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void markdownExportRequiresAdminRole() throws Exception {
        mockMvc.perform(get(EXPORT_URL))
                .andExpect(status().isUnauthorized());

        User viewer = new User();
        viewer.setUsername("markdown-viewer");
        viewer.setPassword(passwordEncoder.encode("Viewer!Pass2026"));
        viewer.setNickname("Markdown Viewer");
        viewer.setRole("user");
        viewer.setStatus(true);
        viewer.setTokenVersion(0);
        viewer.setCreatedAt(LocalDateTime.now());
        viewer.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(viewer);

        mockMvc.perform(get(EXPORT_URL).header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportsReadableCompleteAndSafelyEscapedMarkdownInStableOrder() throws Exception {
        clearNavigation();
        LocalDateTime now = LocalDateTime.of(2026, 8, 14, 10, 0);
        Category alpha = insertCategory("Alpha *visible*\nsecond", "A", 10, true, now);
        Category beta = insertCategory("Beta [tie]", null, 10, true, now.plusSeconds(1));
        Category hidden = insertCategory("Hidden <group>", "https://icons.example/c.png", 20, false,
                now.plusSeconds(2));

        insertBookmark(alpha.getId(), "Late bookmark", "javascript:alert(1)", null, null,
                20, false, false, true, now);
        insertBookmark(alpha.getId(), "Unsafe ] <script>\nline",
                "https://example.com/a_(b)?q=x>y\\z", "https://icons.example/a.png",
                "first\n- injected\t<!-- -->\u202E", 10, true, true, false, now.plusSeconds(1));
        insertBookmark(alpha.getId(), "Tie bookmark", "https://example.net/tie", null, null,
                10, false, true, true, now.plusSeconds(2));
        insertBookmark(beta.getId(), "Beta bookmark", "http://example.org/path", "B", "Beta description",
                0, false, true, true, now.plusSeconds(3));

        MvcResult result = mockMvc.perform(get(EXPORT_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        Matchers.matchesPattern("^attachment;.*yunlume-bookmarks-\\d{8}-\\d{6}\\.md.*$")))
                .andReturn();

        String markdown = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(markdown).startsWith("---\nformat: yunlume-bookmarks-markdown-v1\n");
        assertThat(markdown).contains("categoryCount: 3", "bookmarkCount: 4", "包括隐藏内容");

        int alphaHeading = markdown.indexOf("## 1. Alpha \\*visible\\* second");
        int betaHeading = markdown.indexOf("## 2. Beta \\[tie\\]");
        int hiddenHeading = markdown.indexOf("## 3. Hidden \\<group\\>");
        assertThat(alphaHeading).isGreaterThanOrEqualTo(0);
        assertThat(betaHeading).isGreaterThan(alphaHeading);
        assertThat(hiddenHeading).isGreaterThan(betaHeading);

        int earlyBookmark = markdown.indexOf("Unsafe \\] \\<script\\> line");
        int tieBookmark = markdown.indexOf("Tie bookmark");
        int lateBookmark = markdown.indexOf("Late bookmark");
        assertThat(earlyBookmark).isGreaterThan(alphaHeading);
        assertThat(tieBookmark).isGreaterThan(earlyBookmark);
        assertThat(lateBookmark).isGreaterThan(tieBookmark);
        assertThat(markdown).contains(
                "](<https://example.com/a_(b)?q=x%3Ey%5Cz>)",
                "- 状态：隐藏",
                "- 推荐：是",
                "- 打开方式：新窗口",
                "图标：https\\:\\/\\/icons\\.example\\/a\\.png",
                "简介：first \\- injected \\<\\!\\-\\- \\-\\-\\>"
        );
        assertThat(markdown).contains("地址：javascript\\:alert\\(1\\)");
        assertThat(markdown).doesNotContain("](<javascript:", "<script>", "<!--", "\u202E");

        assertThat(markdown.substring(hiddenHeading)).contains("分类状态：隐藏", "_此分类暂无书签。_");
        assertThat(markdown).doesNotContain(
                "categoryId", "bookmarkId", "createdAt", "updatedAt", "revision",
                admin().getUsername(), admin().getPassword()
        );
    }

    @Test
    void emptyNavigationStillReturnsANonEmptyValidDocument() throws Exception {
        clearNavigation();

        MvcResult result = mockMvc.perform(get(EXPORT_URL)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8)))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        String markdown = new String(body, StandardCharsets.UTF_8);
        assertThat(markdown).contains("categoryCount: 0", "bookmarkCount: 0", "_当前没有分类或书签。_");
    }

    private void clearNavigation() {
        bookmarkMapper.delete(null);
        categoryMapper.delete(null);
    }

    private Category insertCategory(
            String name,
            String icon,
            int sortOrder,
            boolean visible,
            LocalDateTime timestamp
    ) {
        Category category = new Category();
        category.setName(name);
        category.setIcon(icon);
        category.setSortOrder(sortOrder);
        category.setVisible(visible);
        category.setCreatedAt(timestamp);
        category.setUpdatedAt(timestamp);
        categoryMapper.insert(category);
        return category;
    }

    private Bookmark insertBookmark(
            Long categoryId,
            String name,
            String url,
            String icon,
            String description,
            int sortOrder,
            boolean recommend,
            boolean external,
            boolean visible,
            LocalDateTime timestamp
    ) {
        Bookmark bookmark = new Bookmark();
        bookmark.setCategoryId(categoryId);
        bookmark.setName(name);
        bookmark.setUrl(url);
        bookmark.setIcon(icon);
        bookmark.setDescription(description);
        bookmark.setSortOrder(sortOrder);
        bookmark.setRecommend(recommend);
        bookmark.setExternal(external);
        bookmark.setVisible(visible);
        bookmark.setCreatedAt(timestamp);
        bookmark.setUpdatedAt(timestamp);
        bookmarkMapper.insert(bookmark);
        return bookmark;
    }

    private User admin() {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, "admin")
                .last("LIMIT 1"));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.createToken(user);
    }
}
