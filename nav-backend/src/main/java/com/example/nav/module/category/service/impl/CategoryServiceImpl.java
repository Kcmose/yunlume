package com.example.nav.module.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.bookmark.entity.Bookmark;
import com.example.nav.module.bookmark.mapper.BookmarkMapper;
import com.example.nav.module.category.dto.CategoryCreateDTO;
import com.example.nav.module.category.dto.CategoryUpdateDTO;
import com.example.nav.module.category.entity.Category;
import com.example.nav.module.category.mapper.CategoryMapper;
import com.example.nav.module.category.service.CategoryService;
import com.example.nav.module.category.vo.CategoryVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CategoryServiceImpl implements CategoryService {

    private static final int SORT_STEP = 10;
    private static final int MAX_BATCH_ITEMS = 1000;

    private final CategoryMapper categoryMapper;
    private final BookmarkMapper bookmarkMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper, BookmarkMapper bookmarkMapper) {
        this.categoryMapper = categoryMapper;
        this.bookmarkMapper = bookmarkMapper;
    }

    @Override
    public List<CategoryVO> listAll() {
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .orderByAsc(Category::getSortOrder, Category::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<CategoryVO> listVisible() {
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .eq(Category::getVisible, true)
                        .orderByAsc(Category::getSortOrder, Category::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public CategoryVO create(CategoryCreateDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        Category category = new Category();
        category.setName(dto.name().trim());
        category.setIcon(dto.icon());
        category.setSortOrder(dto.sortOrder() == null ? nextSortOrder() : dto.sortOrder());
        category.setVisible(dto.visible() == null || dto.visible());
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        categoryMapper.insert(category);
        return toVO(category);
    }

    @Override
    public CategoryVO update(Long id, CategoryUpdateDTO dto) {
        Category category = requireCategory(id);
        category.setName(dto.name().trim());
        category.setIcon(dto.icon());
        if (dto.sortOrder() != null) category.setSortOrder(dto.sortOrder());
        if (dto.visible() != null) category.setVisible(dto.visible());
        category.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(category);
        return toVO(category);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireCategoryForUpdate(id);
        long count = bookmarkMapper.selectCount(Wrappers.<Bookmark>lambdaQuery().eq(Bookmark::getCategoryId, id));
        if (count > 0) {
            throw BusinessException.conflict("该分类下仍有书签，请先移动或删除书签");
        }
        categoryMapper.deleteById(id);
    }

    @Override
    public CategoryVO setVisible(Long id, boolean visible) {
        Category category = requireCategory(id);
        category.setVisible(visible);
        category.setUpdatedAt(LocalDateTime.now());
        categoryMapper.updateById(category);
        return toVO(category);
    }

    @Override
    @Transactional
    public List<CategoryVO> sort(List<SortItemDTO> items) {
        Map<Long, Category> categoriesById = validateSortItems(items);
        LocalDateTime now = LocalDateTime.now();
        for (SortItemDTO item : items) {
            Category category = categoriesById.get(item.id());
            category.setSortOrder(item.sortOrder());
            category.setUpdatedAt(now);
            if (categoryMapper.updateSortOrder(
                    category.getId(), category.getSortOrder(), category.getUpdatedAt()
            ) != 1) {
                throw BusinessException.conflict("分类状态已变化，请刷新后重试");
            }
        }
        return listAll();
    }

    private Map<Long, Category> validateSortItems(List<SortItemDTO> items) {
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

        Map<Long, Category> categoriesById = new HashMap<>();
        categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .in(Category::getId, ids)
                        .last("FOR UPDATE"))
                .forEach(category -> categoriesById.put(category.getId(), category));
        for (Long id : ids) {
            if (!categoriesById.containsKey(id)) {
                throw BusinessException.notFound("分类不存在: " + id);
            }
        }
        return categoriesById;
    }

    private int nextSortOrder() {
        Category last = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .orderByDesc(Category::getSortOrder)
                .last("LIMIT 1"));
        if (last == null || last.getSortOrder() == null) return 0;
        long next = (long) last.getSortOrder() + SORT_STEP;
        if (next > Integer.MAX_VALUE) {
            throw BusinessException.conflict("分类排序值已达到上限，请先调整排序");
        }
        return (int) Math.max(0L, next);
    }

    private Category requireCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if (category == null) {
            throw BusinessException.notFound("分类不存在");
        }
        return category;
    }

    private Category requireCategoryForUpdate(Long id) {
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .eq(Category::getId, id)
                .last("FOR UPDATE"));
        if (category == null) {
            throw BusinessException.notFound("分类不存在");
        }
        return category;
    }

    private CategoryVO toVO(Category category) {
        long count = bookmarkMapper.selectCount(Wrappers.<Bookmark>lambdaQuery()
                .eq(Bookmark::getCategoryId, category.getId()));
        return new CategoryVO(
                category.getId(), category.getName(), category.getIcon() == null ? "" : category.getIcon(),
                category.getSortOrder(),
                category.getVisible(), count, category.getCreatedAt(), category.getUpdatedAt()
        );
    }
}
