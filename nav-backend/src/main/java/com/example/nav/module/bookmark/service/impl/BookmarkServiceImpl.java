package com.example.nav.module.bookmark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.bookmark.dto.BookmarkBatchMoveDTO;
import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.bookmark.dto.BookmarkUpdateDTO;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.bookmark.service.BookmarkService;
import com.example.nav.module.bookmark.vo.BookmarkVO;
import com.example.nav.module.publicdata.PublicDataCacheNames;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import org.springframework.cache.annotation.CacheEvict;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BookmarkServiceImpl implements BookmarkService {

    private static final int SORT_STEP = 10;
    private static final int MAX_BATCH_ITEMS = 1000;

    private final BookmarkMapper bookmarkMapper;
    private final CategoryMapper categoryMapper;
    private final PublicDataCacheInvalidator cacheInvalidator;

    public BookmarkServiceImpl(
            BookmarkMapper bookmarkMapper,
            CategoryMapper categoryMapper,
            PublicDataCacheInvalidator cacheInvalidator
    ) {
        this.bookmarkMapper = bookmarkMapper;
        this.categoryMapper = categoryMapper;
        this.cacheInvalidator = cacheInvalidator;
    }

    @Override
    public List<BookmarkVO> list(Long categoryId) {
        return select(categoryId, false);
    }

    @Override
    public List<BookmarkVO> listVisible(Long categoryId) {
        return select(categoryId, true);
    }

    @Override
    public List<BookmarkVO> listVisibleByCategoryIds(List<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) return List.of();
        return bookmarkMapper.selectList(Wrappers.<Bookmark>lambdaQuery()
                        .in(Bookmark::getCategoryId, categoryIds)
                        .eq(Bookmark::getVisible, true)
                        .orderByAsc(Bookmark::getSortOrder, Bookmark::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public BookmarkVO create(BookmarkCreateDTO dto) {
        requireAppendCategory(dto.categoryId());
        LocalDateTime now = LocalDateTime.now();
        Bookmark bookmark = new Bookmark();
        bookmark.setCategoryId(dto.categoryId());
        bookmark.setName(dto.name().trim());
        bookmark.setUrl(dto.url().trim());
        bookmark.setIcon(dto.icon());
        bookmark.setDescription(dto.description());
        bookmark.setSortOrder(dto.sortOrder() == null ? nextSortOrder(dto.categoryId()) : dto.sortOrder());
        bookmark.setRecommend(Boolean.TRUE.equals(dto.isRecommend()));
        bookmark.setExternal(dto.isExternal() == null || dto.isExternal());
        bookmark.setVisible(dto.visible() == null || dto.visible());
        bookmark.setCreatedAt(now);
        bookmark.setUpdatedAt(now);
        bookmarkMapper.insert(bookmark);
        invalidateNavigation();
        return toVO(bookmark);
    }

    @Override
    @Transactional
    public BookmarkVO update(Long id, BookmarkUpdateDTO dto) {
        Bookmark bookmark = requireBookmark(id);
        requireCategory(dto.categoryId());
        bookmark.setCategoryId(dto.categoryId());
        bookmark.setName(dto.name().trim());
        bookmark.setUrl(dto.url().trim());
        bookmark.setIcon(dto.icon());
        bookmark.setDescription(dto.description());
        if (dto.sortOrder() != null) bookmark.setSortOrder(dto.sortOrder());
        if (dto.isRecommend() != null) bookmark.setRecommend(dto.isRecommend());
        if (dto.isExternal() != null) bookmark.setExternal(dto.isExternal());
        if (dto.visible() != null) bookmark.setVisible(dto.visible());
        bookmark.setUpdatedAt(LocalDateTime.now());
        bookmarkMapper.updateById(bookmark);
        invalidateNavigation();
        return toVO(bookmark);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireBookmark(id);
        bookmarkMapper.deleteById(id);
        invalidateNavigation();
    }

    @Override
    @Transactional
    public BookmarkVO setVisible(Long id, boolean visible) {
        Bookmark bookmark = requireBookmark(id);
        bookmark.setVisible(visible);
        bookmark.setUpdatedAt(LocalDateTime.now());
        bookmarkMapper.updateById(bookmark);
        invalidateNavigation();
        return toVO(bookmark);
    }

    @Override
    @Transactional
    public List<BookmarkVO> batchMove(BookmarkBatchMoveDTO dto) {
        LinkedHashSet<Long> ids = validateMoveRequest(dto);
        requireTargetCategory(dto.categoryId());
        Map<Long, Bookmark> bookmarksById = loadBookmarks(ids);

        long movingCount = ids.stream()
                .map(bookmarksById::get)
                .filter(bookmark -> !dto.categoryId().equals(bookmark.getCategoryId()))
                .count();
        if (movingCount == 0) {
            return ids.stream().map(bookmarksById::get).map(this::toVO).toList();
        }

        long nextSortOrder = firstAppendSortOrder(dto.categoryId(), Math.toIntExact(movingCount));
        LocalDateTime now = LocalDateTime.now();
        for (Long id : ids) {
            Bookmark bookmark = bookmarksById.get(id);
            if (dto.categoryId().equals(bookmark.getCategoryId())) continue;
            bookmark.setCategoryId(dto.categoryId());
            bookmark.setSortOrder((int) nextSortOrder);
            bookmark.setUpdatedAt(now);
            if (bookmarkMapper.moveToCategory(
                    bookmark.getId(), bookmark.getCategoryId(), bookmark.getSortOrder(), bookmark.getUpdatedAt()
            ) != 1) {
                throw BusinessException.conflict("书签状态已变化，请刷新后重试");
            }
            nextSortOrder += SORT_STEP;
        }

        invalidateNavigation();
        return ids.stream().map(bookmarksById::get).map(this::toVO).toList();
    }

    @Override
    @Transactional
    public List<BookmarkVO> sort(List<SortItemDTO> items) {
        Map<Long, Bookmark> bookmarksById = validateSortItems(items);
        LocalDateTime now = LocalDateTime.now();
        for (SortItemDTO item : items) {
            Bookmark bookmark = bookmarksById.get(item.id());
            bookmark.setSortOrder(item.sortOrder());
            bookmark.setUpdatedAt(now);
            if (bookmarkMapper.updateSortOrder(
                    bookmark.getId(), bookmark.getSortOrder(), bookmark.getUpdatedAt()
            ) != 1) {
                throw BusinessException.conflict("书签状态已变化，请刷新后重试");
            }
        }
        invalidateNavigation();
        return list(null);
    }

    private void invalidateNavigation() {
        cacheInvalidator.invalidate(PublicDataCacheNames.NAVIGATION);
    }

    private List<BookmarkVO> select(Long categoryId, boolean onlyVisible) {
        LambdaQueryWrapper<Bookmark> query = Wrappers.lambdaQuery();
        if (categoryId != null) query.eq(Bookmark::getCategoryId, categoryId);
        if (onlyVisible) query.eq(Bookmark::getVisible, true);
        query.orderByAsc(Bookmark::getSortOrder, Bookmark::getId);
        return bookmarkMapper.selectList(query).stream().map(this::toVO).toList();
    }

    private int nextSortOrder(Long categoryId) {
        Bookmark last = bookmarkMapper.selectOne(Wrappers.<Bookmark>lambdaQuery()
                .eq(Bookmark::getCategoryId, categoryId)
                .orderByDesc(Bookmark::getSortOrder)
                .last("LIMIT 1"));
        if (last == null || last.getSortOrder() == null) return 0;
        long next = (long) last.getSortOrder() + SORT_STEP;
        if (next > Integer.MAX_VALUE) {
            throw BusinessException.conflict("分类内书签排序值已达到上限，请先调整排序");
        }
        return (int) Math.max(0L, next);
    }

    private Map<Long, Bookmark> validateSortItems(List<SortItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("排序列表不能为空");
        }
        if (items.size() > MAX_BATCH_ITEMS) {
            throw BusinessException.badRequest("排序列表不能超过 1000 项");
        }

        Set<Long> ids = new HashSet<>();
        for (SortItemDTO item : items) {
            if (item == null || item.id() == null || item.id() <= 0) {
                throw BusinessException.badRequest("排序项 ID 必须大于 0");
            }
            if (item.sortOrder() == null || item.sortOrder() < 0) {
                throw BusinessException.badRequest("排序值不能小于 0");
            }
            if (!ids.add(item.id())) {
                throw BusinessException.badRequest("排序列表包含重复 ID");
            }
        }

        return loadBookmarks(ids);
    }

    private LinkedHashSet<Long> validateMoveRequest(BookmarkBatchMoveDTO dto) {
        if (dto == null || dto.ids() == null || dto.ids().isEmpty()) {
            throw BusinessException.badRequest("书签 ID 列表不能为空");
        }
        if (dto.ids().size() > MAX_BATCH_ITEMS) {
            throw BusinessException.badRequest("单次最多移动 1000 个书签");
        }
        if (dto.categoryId() == null || dto.categoryId() <= 0) {
            throw BusinessException.badRequest("目标分类 ID 必须大于 0");
        }

        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : dto.ids()) {
            if (id == null || id <= 0) {
                throw BusinessException.badRequest("书签 ID 必须大于 0");
            }
            if (!ids.add(id)) {
                throw BusinessException.badRequest("书签 ID 列表包含重复 ID");
            }
        }
        return ids;
    }

    private Map<Long, Bookmark> loadBookmarks(Set<Long> ids) {
        Map<Long, Bookmark> bookmarksById = new HashMap<>();
        bookmarkMapper.selectList(Wrappers.<Bookmark>lambdaQuery()
                        .in(Bookmark::getId, ids)
                        .last("FOR UPDATE"))
                .forEach(bookmark -> bookmarksById.put(bookmark.getId(), bookmark));
        for (Long id : ids) {
            if (!bookmarksById.containsKey(id)) {
                throw BusinessException.notFound("书签不存在: " + id);
            }
        }
        return bookmarksById;
    }

    private int firstAppendSortOrder(Long categoryId, int itemCount) {
        Bookmark last = bookmarkMapper.selectOne(Wrappers.<Bookmark>lambdaQuery()
                .eq(Bookmark::getCategoryId, categoryId)
                .orderByDesc(Bookmark::getSortOrder)
                .orderByDesc(Bookmark::getId)
                .last("LIMIT 1"));
        long first = last == null || last.getSortOrder() == null
                ? 0L
                : Math.max(0L, (long) last.getSortOrder() + SORT_STEP);
        long finalSortOrder = first + (long) (itemCount - 1) * SORT_STEP;
        if (finalSortOrder > Integer.MAX_VALUE) {
            throw BusinessException.conflict("目标分类排序值已达到上限，请先调整排序");
        }
        return (int) first;
    }

    private void requireCategory(Long id) {
        if (categoryMapper.selectById(id) == null) {
            throw BusinessException.badRequest("所属分类不存在");
        }
    }

    private void requireTargetCategory(Long id) {
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .eq(Category::getId, id)
                .last("FOR UPDATE"));
        if (category == null) {
            throw BusinessException.notFound("目标分类不存在");
        }
    }

    private void requireAppendCategory(Long id) {
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .eq(Category::getId, id)
                .last("FOR UPDATE"));
        if (category == null) {
            throw BusinessException.badRequest("所属分类不存在");
        }
    }

    private Bookmark requireBookmark(Long id) {
        Bookmark bookmark = bookmarkMapper.selectById(id);
        if (bookmark == null) {
            throw BusinessException.notFound("书签不存在");
        }
        return bookmark;
    }

    private BookmarkVO toVO(Bookmark bookmark) {
        return new BookmarkVO(
                bookmark.getId(), bookmark.getCategoryId(), bookmark.getName(), bookmark.getUrl(),
                bookmark.getIcon() == null ? "" : bookmark.getIcon(),
                bookmark.getDescription() == null ? "" : bookmark.getDescription(), bookmark.getSortOrder(),
                bookmark.getRecommend(), bookmark.getExternal(), bookmark.getVisible(),
                bookmark.getCreatedAt(), bookmark.getUpdatedAt()
        );
    }
}
