package com.example.nav.module.datapackage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates a human-readable Markdown copy of every category and bookmark.
 *
 * <p>This is intentionally not a restore format. Database identifiers, timestamps and
 * operational data are omitted; the versioned ZIP export remains the portable restore format.</p>
 */
@Service
public class BookmarkMarkdownExportService {

    static final String FORMAT = "yunlume-bookmarks-markdown-v1";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final CategoryMapper categoryMapper;
    private final BookmarkMapper bookmarkMapper;
    private final Clock clock;

    @Autowired
    public BookmarkMarkdownExportService(CategoryMapper categoryMapper, BookmarkMapper bookmarkMapper) {
        this(categoryMapper, bookmarkMapper, Clock.systemUTC());
    }

    BookmarkMarkdownExportService(CategoryMapper categoryMapper, BookmarkMapper bookmarkMapper, Clock clock) {
        this.categoryMapper = categoryMapper;
        this.bookmarkMapper = bookmarkMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportedMarkdown export() {
        List<Category> categories = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .orderByAsc(Category::getSortOrder, Category::getId));
        List<Bookmark> bookmarks = bookmarkMapper.selectList(Wrappers.<Bookmark>lambdaQuery()
                .orderByAsc(Bookmark::getCategoryId, Bookmark::getSortOrder, Bookmark::getId));

        Map<Long, List<Bookmark>> bookmarksByCategory = new LinkedHashMap<>();
        for (Category category : categories) {
            bookmarksByCategory.put(category.getId(), new ArrayList<>());
        }
        for (Bookmark bookmark : bookmarks) {
            List<Bookmark> categoryBookmarks = bookmarksByCategory.get(bookmark.getCategoryId());
            if (categoryBookmarks == null) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "书签分类关系异常，无法生成可靠备份");
            }
            categoryBookmarks.add(bookmark);
        }

        Instant exportedAt = clock.instant();
        String markdown = render(categories, bookmarksByCategory, bookmarks.size(), exportedAt);
        return new ExportedMarkdown(
                "yunlume-bookmarks-" + FILE_TIME.format(exportedAt) + ".md",
                markdown.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String render(
            List<Category> categories,
            Map<Long, List<Bookmark>> bookmarksByCategory,
            int bookmarkCount,
            Instant exportedAt
    ) {
        int initialCapacity = (int) Math.min(1_048_576L, Math.max(512L, (long) bookmarkCount * 256L));
        StringBuilder markdown = new StringBuilder(initialCapacity);
        markdown.append("---\n")
                .append("format: ").append(FORMAT).append('\n')
                .append("exportedAt: \"").append(exportedAt).append("\"\n")
                .append("categoryCount: ").append(categories.size()).append('\n')
                .append("bookmarkCount: ").append(bookmarkCount).append('\n')
                .append("---\n\n")
                .append("# 书签备份\n\n")
                .append("> 导出时间：").append(exportedAt).append("\n")
                .append("> 范围：全部分类与书签（包括隐藏内容）\n")
                .append("> 用途：人类可读副本，不能用于数据恢复；恢复请使用 ZIP 数据包。\n")
                .append("> 分类数：").append(categories.size())
                .append("；书签数：").append(bookmarkCount).append("\n\n");

        if (categories.isEmpty()) {
            markdown.append("_当前没有分类或书签。_\n");
            return markdown.toString();
        }

        for (int categoryIndex = 0; categoryIndex < categories.size(); categoryIndex++) {
            Category category = categories.get(categoryIndex);
            markdown.append("## ").append(categoryIndex + 1).append(". ")
                    .append(markdownText(category.getName(), "未命名分类")).append("\n\n")
                    .append("- 分类排序：").append(numberOrZero(category.getSortOrder())).append('\n')
                    .append("- 分类状态：").append(Boolean.TRUE.equals(category.getVisible()) ? "显示" : "隐藏")
                    .append('\n');
            appendOptionalText(markdown, "- 分类图标：", category.getIcon());

            List<Bookmark> categoryBookmarks = bookmarksByCategory.get(category.getId());
            markdown.append("\n### 书签\n\n");
            if (categoryBookmarks == null || categoryBookmarks.isEmpty()) {
                markdown.append("_此分类暂无书签。_\n\n");
                continue;
            }

            for (int bookmarkIndex = 0; bookmarkIndex < categoryBookmarks.size(); bookmarkIndex++) {
                Bookmark bookmark = categoryBookmarks.get(bookmarkIndex);
                String name = markdownText(bookmark.getName(), "未命名书签");
                String safeUrl = safeHttpUrl(bookmark.getUrl());
                markdown.append(bookmarkIndex + 1).append(". ");
                if (safeUrl == null) {
                    markdown.append(name).append('\n');
                    appendOptionalText(markdown, "   - 地址：", bookmark.getUrl());
                } else {
                    markdown.append('[').append(name).append("](<").append(safeUrl).append(">)\n");
                }
                markdown.append("   - 排序：").append(numberOrZero(bookmark.getSortOrder())).append('\n')
                        .append("   - 状态：")
                        .append(Boolean.TRUE.equals(bookmark.getVisible()) ? "显示" : "隐藏").append('\n')
                        .append("   - 推荐：")
                        .append(Boolean.TRUE.equals(bookmark.getRecommend()) ? "是" : "否").append('\n')
                        .append("   - 打开方式：")
                        .append(Boolean.TRUE.equals(bookmark.getExternal()) ? "新窗口" : "当前窗口").append('\n');
                appendOptionalText(markdown, "   - 图标：", bookmark.getIcon());
                appendOptionalText(markdown, "   - 简介：", bookmark.getDescription());
                markdown.append('\n');
            }
        }
        return markdown.toString();
    }

    private void appendOptionalText(StringBuilder markdown, String prefix, String value) {
        if (value == null || value.isBlank()) return;
        String rendered = markdownText(value, "");
        if (!rendered.isEmpty()) {
            markdown.append(prefix).append(rendered).append('\n');
        }
    }

    private int numberOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Normalizes line/control characters before escaping all CommonMark ASCII punctuation.
     * Exact values remain available in the portable ZIP; this copy favours safe display.
     */
    private String markdownText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder normalized = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isUnsafeControl(codePoint) || Character.isWhitespace(codePoint)) {
                if (!previousSpace && !normalized.isEmpty()) {
                    normalized.append(' ');
                    previousSpace = true;
                }
                continue;
            }
            if (codePoint <= 0x7f && isAsciiPunctuation((char) codePoint)) {
                normalized.append('\\');
            }
            normalized.appendCodePoint(codePoint);
            previousSpace = false;
        }
        String result = normalized.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private boolean isUnsafeControl(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == 0x061c
                || codePoint == 0x200e
                || codePoint == 0x200f
                || (codePoint >= 0x202a && codePoint <= 0x202e)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private boolean isAsciiPunctuation(char value) {
        return (value >= '!' && value <= '/')
                || (value >= ':' && value <= '@')
                || (value >= '[' && value <= '`')
                || (value >= '{' && value <= '~');
    }

    /**
     * Only HTTP(S) values become links. Unsafe bytes are percent-encoded and the destination
     * is additionally wrapped in CommonMark's angle-bracket form.
     */
    private String safeHttpUrl(String value) {
        if (value == null || !(value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8))) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder safe = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int unsigned = current & 0xff;
            if (isRfc3986Byte(unsigned)) {
                safe.append((char) unsigned);
            } else {
                safe.append('%');
                String hex = Integer.toHexString(unsigned).toUpperCase(Locale.ROOT);
                if (hex.length() == 1) safe.append('0');
                safe.append(hex);
            }
        }
        return safe.toString();
    }

    private boolean isRfc3986Byte(int value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || "-._~:/?#[]@!$&'()*+,;=%".indexOf(value) >= 0;
    }

    public record ExportedMarkdown(String filename, byte[] bytes) {
    }
}
