package com.example.nav.common.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.customlink.entity.CustomLink;
import com.example.nav.module.customlink.mapper.CustomLinkMapper;
import com.example.nav.module.search.entity.SearchEngine;
import com.example.nav.module.search.mapper.SearchEngineMapper;
import com.example.nav.module.site.entity.SiteConfig;
import com.example.nav.module.site.mapper.SiteConfigMapper;
import com.example.nav.module.user.entity.User;
import com.example.nav.module.user.mapper.UserMapper;
import com.example.nav.common.security.PasswordPolicy;
import com.example.nav.module.install.service.DatabaseConfigurationStore;
import com.example.nav.module.install.service.DatabaseIdentityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@Order(0)
public class DataInitializer implements ApplicationRunner {

    private final BootstrapProperties properties;
    private final WebInstallProperties webInstallProperties;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final SiteConfigMapper siteConfigMapper;
    private final CategoryMapper categoryMapper;
    private final BookmarkMapper bookmarkMapper;
    private final SearchEngineMapper searchEngineMapper;
    private final CustomLinkMapper customLinkMapper;
    private final DatabaseConfigurationStore databaseConfigurationStore;
    private final DatabaseIdentityService databaseIdentityService;

    public DataInitializer(
            BootstrapProperties properties,
            WebInstallProperties webInstallProperties,
            PasswordEncoder passwordEncoder,
            UserMapper userMapper,
            SiteConfigMapper siteConfigMapper,
            CategoryMapper categoryMapper,
            BookmarkMapper bookmarkMapper,
            SearchEngineMapper searchEngineMapper,
            CustomLinkMapper customLinkMapper,
            DatabaseConfigurationStore databaseConfigurationStore,
            DatabaseIdentityService databaseIdentityService
    ) {
        this.properties = properties;
        this.webInstallProperties = webInstallProperties;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.siteConfigMapper = siteConfigMapper;
        this.categoryMapper = categoryMapper;
        this.bookmarkMapper = bookmarkMapper;
        this.searchEngineMapper = searchEngineMapper;
        this.customLinkMapper = customLinkMapper;
        this.databaseConfigurationStore = databaseConfigurationStore;
        this.databaseIdentityService = databaseIdentityService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("Data bootstrap is disabled");
            return;
        }
        if (databaseConfigurationStore.isUnconfiguredSource()
                || databaseConfigurationStore.hasInvalidOrPendingArtifact()
                || (databaseConfigurationStore.hasPersistedConnection()
                    && !databaseIdentityService.isIdentityRequired())) {
            log.info("Data bootstrap is waiting for committed database configuration");
            return;
        }
        if (!databaseIdentityService.ensureVerified()) {
            log.error("Data bootstrap is blocked because database identity could not be verified");
            return;
        }
        try {
            initializeData();
        } catch (DataAccessException exception) {
            if (!webInstallProperties.isEnabled()) {
                throw exception;
            }
            log.warn("Database bootstrap is waiting for the first-install database configuration");
        }
    }

    private void initializeData() {
        if (userMapper.selectCount(null) == 0
                && siteConfigMapper.countCompletedInstallations() > 0) {
            log.error("Installation is marked complete but no administrator exists; automatic bootstrap is blocked");
            return;
        }
        seedAdmin();
        if (properties.isDemoDataEnabled()) {
            seedSiteConfig();
            seedNavigation();
            seedSearchEngines();
            seedCustomLinks();
        } else {
            log.info("Demo data bootstrap is disabled; preserving production business data");
        }
        siteConfigMapper.markInstallationCompletedWhenUserExists(LocalDateTime.now());
    }

    private void seedAdmin() {
        if (userMapper.selectCount(null) > 0) {
            return;
        }

        String username = properties.getAdminUsername() == null
                ? null
                : properties.getAdminUsername().trim();
        String password = properties.getAdminPassword();
        if ((password == null || password.isBlank()) && webInstallProperties.isEnabled()) {
            log.info("Bootstrap administrator password is empty; waiting for one-time web installation");
            return;
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Bootstrap administrator username and password must not be blank");
        }
        PasswordPolicy.findViolation(username, password).ifPresent(message -> {
            throw new IllegalStateException("Bootstrap administrator password violates policy: " + message);
        });

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname("管理员");
        user.setRole("admin");
        user.setStatus(true);
        user.setTokenVersion(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        log.info("Created the first bootstrap administrator '{}'", username);
    }

    private void seedSiteConfig() {
        if (siteConfigMapper.selectCount(null) > 0) return;
        LocalDateTime now = LocalDateTime.now();
        SiteConfig config = new SiteConfig();
        config.setSiteName("yunlume");
        config.setSiteDescription("发现、收藏并快速抵达常用站点");
        config.setPublishUrl("/");
        config.setBackgroundType("color");
        config.setBackgroundColor("#050505");
        config.setFontColor("#ffffff");
        config.setBackgroundEffect(false);
        config.setMusicEnabled(false);
        config.setSubscribeEnabled(false);
        config.setTopContentEnabled(true);
        config.setMessageText("保持专注，探索互联网");
        config.setVersion(0);
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        siteConfigMapper.insert(config);
    }

    private void seedNavigation() {
        if (categoryMapper.selectCount(null) == 0) {
            insertCategory("常用推荐", "✨", 10);
            insertCategory("AI 工具", "✦", 20);
            insertCategory("开发设计", "⌘", 30);
            insertCategory("效率办公", "◫", 40);
            insertCategory("影音娱乐", "▷", 50);
            insertCategory("学习资源", "◇", 60);
        }
        if (bookmarkMapper.selectCount(null) > 0) return;

        List<Category> categories = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .orderByAsc(Category::getSortOrder, Category::getId));
        if (categories.isEmpty()) return;
        Long common = categories.get(0).getId();
        Long ai = categories.get(Math.min(1, categories.size() - 1)).getId();
        Long dev = categories.get(Math.min(2, categories.size() - 1)).getId();
        Long office = categories.get(Math.min(3, categories.size() - 1)).getId();
        Long media = categories.get(Math.min(4, categories.size() - 1)).getId();
        Long study = categories.get(Math.min(5, categories.size() - 1)).getId();

        insertBookmark(common, "GitHub", "https://github.com", "代码托管与协作", 10, true);
        insertBookmark(common, "Google", "https://www.google.com", "搜索互联网", 20, false);
        insertBookmark(ai, "ChatGPT", "https://chatgpt.com", "AI 助手", 10, true);
        insertBookmark(dev, "MDN", "https://developer.mozilla.org", "Web 开发文档", 10, false);
        insertBookmark(dev, "Figma", "https://www.figma.com", "界面设计与协作", 20, false);
        insertBookmark(office, "Notion", "https://www.notion.so", "知识与项目管理", 10, false);
        insertBookmark(media, "哔哩哔哩", "https://www.bilibili.com", "视频与创作社区", 10, false);
        insertBookmark(study, "Wikipedia", "https://www.wikipedia.org", "自由百科全书", 10, false);
    }

    private void seedSearchEngines() {
        if (searchEngineMapper.selectCount(null) > 0) return;
        insertSearchEngine("百度", "https://www.baidu.com/s?wd={keyword}", "百度一下", true, 10);
        insertSearchEngine("Bing", "https://www.bing.com/search?q={keyword}", "使用 Bing 搜索", false, 20);
        insertSearchEngine("Google", "https://www.google.com/search?q={keyword}", "使用 Google 搜索", false, 30);
    }

    private void seedCustomLinks() {
        if (customLinkMapper.selectCount(null) > 0) return;
        insertCustomLink("首页", "/", "header", 10);
        insertCustomLink("管理后台", "/admin", "footer", 10);
    }

    private void insertCategory(String name, String icon, int sortOrder) {
        LocalDateTime now = LocalDateTime.now();
        Category category = new Category();
        category.setName(name);
        category.setIcon(icon);
        category.setSortOrder(sortOrder);
        category.setVisible(true);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        categoryMapper.insert(category);
    }

    private void insertBookmark(Long categoryId, String name, String url, String description,
                                int sortOrder, boolean recommend) {
        LocalDateTime now = LocalDateTime.now();
        Bookmark bookmark = new Bookmark();
        bookmark.setCategoryId(categoryId);
        bookmark.setName(name);
        bookmark.setUrl(url);
        bookmark.setDescription(description);
        bookmark.setSortOrder(sortOrder);
        bookmark.setRecommend(recommend);
        bookmark.setExternal(true);
        bookmark.setVisible(true);
        bookmark.setCreatedAt(now);
        bookmark.setUpdatedAt(now);
        bookmarkMapper.insert(bookmark);
    }

    private void insertSearchEngine(String name, String searchUrl, String placeholder,
                                    boolean defaultEngine, int sortOrder) {
        LocalDateTime now = LocalDateTime.now();
        SearchEngine engine = new SearchEngine();
        engine.setName(name);
        engine.setSearchUrl(searchUrl);
        engine.setPlaceholder(placeholder);
        engine.setDefaultEngine(defaultEngine);
        engine.setSortOrder(sortOrder);
        engine.setCreatedAt(now);
        engine.setUpdatedAt(now);
        searchEngineMapper.insert(engine);
    }

    private void insertCustomLink(String title, String url, String position, int sortOrder) {
        LocalDateTime now = LocalDateTime.now();
        CustomLink link = new CustomLink();
        link.setTitle(title);
        link.setUrl(url);
        link.setPosition(position);
        link.setSortOrder(sortOrder);
        link.setVisible(true);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        customLinkMapper.insert(link);
    }
}
