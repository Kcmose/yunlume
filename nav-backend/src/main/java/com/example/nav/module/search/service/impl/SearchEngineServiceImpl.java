package com.example.nav.module.search.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.search.entity.SearchEngine;
import com.example.nav.module.search.mapper.SearchEngineMapper;
import com.example.nav.module.search.service.SearchEngineService;
import com.example.nav.module.search.vo.SearchEngineVO;
import com.example.nav.module.publicdata.PublicDataCacheNames;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SearchEngineServiceImpl implements SearchEngineService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^{}]+)}");

    private final SearchEngineMapper searchEngineMapper;
    private final PublicDataCacheInvalidator cacheInvalidator;

    public SearchEngineServiceImpl(
            SearchEngineMapper searchEngineMapper,
            PublicDataCacheInvalidator cacheInvalidator
    ) {
        this.searchEngineMapper = searchEngineMapper;
        this.cacheInvalidator = cacheInvalidator;
    }

    @Override
    public List<SearchEngineVO> listAll() {
        return searchEngineMapper.selectList(Wrappers.<SearchEngine>lambdaQuery()
                        .orderByAsc(SearchEngine::getSortOrder, SearchEngine::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<SearchEngineVO> listPublic() {
        return searchEngineMapper.selectList(Wrappers.<SearchEngine>lambdaQuery()
                        .eq(SearchEngine::getVisible, true)
                        .orderByDesc(SearchEngine::getDefaultEngine)
                        .orderByAsc(SearchEngine::getSortOrder, SearchEngine::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public SearchEngineVO create(SearchEngineDTO dto) {
        lockAll();
        long existingCount = searchEngineMapper.selectCount(null);
        LocalDateTime now = LocalDateTime.now();
        SearchEngine engine = new SearchEngine();
        apply(engine, dto);
        engine.setSortOrder(dto.sortOrder() == null ? nextSortOrder() : dto.sortOrder());
        engine.setVisible(existingCount == 0 || dto.visible() == null || dto.visible());
        engine.setDefaultEngine(existingCount == 0);
        engine.setCreatedAt(now);
        engine.setUpdatedAt(now);
        searchEngineMapper.insert(engine);
        ensureSingleDefaultEngine();
        SearchEngineVO created = toVO(searchEngineMapper.selectById(engine.getId()));
        invalidatePublicSearchEngines();
        return created;
    }

    @Override
    @Transactional
    public SearchEngineVO update(Long id, SearchEngineDTO dto) {
        lockAll();
        SearchEngine engine = requireEngine(id);
        boolean hidesCurrentDefault = Boolean.TRUE.equals(engine.getDefaultEngine())
                && Boolean.FALSE.equals(dto.visible());
        SearchEngine replacement = hidesCurrentDefault ? requireVisibleReplacement(id) : null;

        apply(engine, dto);
        if (dto.sortOrder() != null) engine.setSortOrder(dto.sortOrder());
        if (dto.visible() != null) engine.setVisible(dto.visible());
        if (hidesCurrentDefault) engine.setDefaultEngine(false);
        engine.setUpdatedAt(LocalDateTime.now());
        // 可选字段允许主动清空，显式 SET 避免实体更新策略跳过 null。
        if (searchEngineMapper.update(null, Wrappers.<SearchEngine>lambdaUpdate()
                .eq(SearchEngine::getId, id)
                .set(SearchEngine::getName, engine.getName())
                .set(SearchEngine::getIcon, engine.getIcon())
                .set(SearchEngine::getSearchUrl, engine.getSearchUrl())
                .set(SearchEngine::getPlaceholder, engine.getPlaceholder())
                .set(SearchEngine::getSortOrder, engine.getSortOrder())
                .set(SearchEngine::getVisible, engine.getVisible())
                .set(SearchEngine::getDefaultEngine, engine.getDefaultEngine())
                .set(SearchEngine::getUpdatedAt, engine.getUpdatedAt())) != 1) {
            throw BusinessException.conflict("搜索引擎状态已变化，请刷新后重试");
        }

        if (replacement != null) makeDefault(replacement);
        ensureSingleDefaultEngine();
        SearchEngineVO updated = toVO(searchEngineMapper.selectById(id));
        invalidatePublicSearchEngines();
        return updated;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        lockAll();
        SearchEngine engine = requireEngine(id);
        long total = searchEngineMapper.selectCount(null);
        if (total <= 1) {
            throw BusinessException.conflict("至少需要保留一个搜索引擎");
        }
        SearchEngine replacement = Boolean.TRUE.equals(engine.getDefaultEngine())
                ? requireVisibleReplacement(id)
                : null;
        searchEngineMapper.deleteById(id);
        if (replacement != null) makeDefault(replacement);
        ensureSingleDefaultEngine();
        invalidatePublicSearchEngines();
    }

    @Override
    @Transactional
    public SearchEngineVO setDefault(Long id) {
        lockAll();
        SearchEngine engine = requireEngine(id);
        engine.setVisible(true);
        makeDefault(engine);
        SearchEngineVO updated = toVO(searchEngineMapper.selectById(id));
        invalidatePublicSearchEngines();
        return updated;
    }

    @Override
    @Transactional
    public SearchEngineVO setVisible(Long id, boolean visible) {
        lockAll();
        SearchEngine engine = requireEngine(id);
        if (Boolean.valueOf(visible).equals(engine.getVisible())) return toVO(engine);

        SearchEngine replacement = null;
        if (!visible && Boolean.TRUE.equals(engine.getDefaultEngine())) {
            replacement = requireVisibleReplacement(id);
            engine.setDefaultEngine(false);
        }
        engine.setVisible(visible);
        engine.setUpdatedAt(LocalDateTime.now());
        searchEngineMapper.updateById(engine);
        if (replacement != null) makeDefault(replacement);
        ensureSingleDefaultEngine();
        SearchEngineVO updated = toVO(searchEngineMapper.selectById(id));
        invalidatePublicSearchEngines();
        return updated;
    }

    @Override
    @Transactional
    public List<SearchEngineVO> sort(List<SortItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("排序列表不能为空");
        }
        Set<Long> ids = new HashSet<>();
        for (SortItemDTO item : items) {
            if (item == null || item.id() == null || item.id() <= 0) {
                throw BusinessException.badRequest("排序项 ID 必须大于 0");
            }
            if (item.sortOrder() == null || item.sortOrder() < 0) {
                throw BusinessException.badRequest("排序值不能小于 0");
            }
            if (!ids.add(item.id())) throw BusinessException.badRequest("排序列表包含重复 ID");
        }
        lockAll();
        for (SortItemDTO item : items) {
            requireEngine(item.id());
        }

        LocalDateTime now = LocalDateTime.now();
        for (SortItemDTO item : items) {
            SearchEngine engine = requireEngine(item.id());
            engine.setSortOrder(item.sortOrder());
            engine.setUpdatedAt(now);
            searchEngineMapper.updateById(engine);
        }
        invalidatePublicSearchEngines();
        return listAll();
    }

    private void invalidatePublicSearchEngines() {
        cacheInvalidator.invalidate(PublicDataCacheNames.SEARCH_ENGINES);
    }

    private void apply(SearchEngine engine, SearchEngineDTO dto) {
        String searchUrl = dto.searchUrl().trim();
        validateSearchTemplate(searchUrl);
        engine.setName(dto.name().trim());
        engine.setIcon(normalizeNullable(dto.icon()));
        engine.setSearchUrl(searchUrl);
        engine.setPlaceholder(normalizeNullable(dto.placeholder()));
    }

    private void validateSearchTemplate(String template) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            if (!"keyword".equals(matcher.group(1))) {
                throw BusinessException.badRequest("搜索地址模板只支持 {keyword} 占位符");
            }
        }

        int authorityStart = template.indexOf("://") + 3;
        int authorityEnd = template.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = template.indexOf(delimiter, authorityStart);
            if (index >= 0 && index < authorityEnd) authorityEnd = index;
        }
        if (template.substring(authorityStart, authorityEnd).contains("{")) {
            throw BusinessException.badRequest("搜索占位符不能出现在主机名中");
        }
        int fragmentIndex = template.indexOf('#');
        if (fragmentIndex >= 0 && template.substring(fragmentIndex).contains("{keyword}")) {
            throw BusinessException.badRequest("搜索占位符不能出现在 URL 片段中");
        }

        try {
            URI uri = URI.create(template.replace("{keyword}", "keyword"));
            boolean validScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (!validScheme || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                throw BusinessException.badRequest("搜索地址模板必须包含有效主机名且不能包含用户信息");
            }
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("搜索地址模板格式无效");
        }
    }

    private int nextSortOrder() {
        SearchEngine last = searchEngineMapper.selectOne(Wrappers.<SearchEngine>lambdaQuery()
                .orderByDesc(SearchEngine::getSortOrder)
                .last("LIMIT 1"));
        if (last == null || last.getSortOrder() == null) return 0;
        long next = (long) last.getSortOrder() + 10;
        if (next > Integer.MAX_VALUE) {
            throw BusinessException.conflict("搜索引擎排序值已达到上限，请先调整排序");
        }
        return (int) Math.max(0L, next);
    }

    private SearchEngine requireEngine(Long id) {
        SearchEngine engine = searchEngineMapper.selectById(id);
        if (engine == null) throw BusinessException.notFound("搜索引擎不存在");
        return engine;
    }

    private SearchEngine requireVisibleReplacement(Long excludedId) {
        SearchEngine replacement = searchEngineMapper.selectOne(Wrappers.<SearchEngine>lambdaQuery()
                .eq(SearchEngine::getVisible, true)
                .ne(SearchEngine::getId, excludedId)
                .orderByAsc(SearchEngine::getSortOrder, SearchEngine::getId)
                .last("LIMIT 1"));
        if (replacement == null) {
            throw BusinessException.conflict("至少需要保留一个启用的搜索引擎");
        }
        return replacement;
    }

    private void ensureSingleDefaultEngine() {
        List<SearchEngine> defaults = searchEngineMapper.selectList(Wrappers.<SearchEngine>lambdaQuery()
                .eq(SearchEngine::getDefaultEngine, true)
                .eq(SearchEngine::getVisible, true)
                .orderByAsc(SearchEngine::getSortOrder, SearchEngine::getId));
        if (defaults.size() == 1) return;
        if (!defaults.isEmpty()) {
            makeDefault(defaults.get(0));
            return;
        }

        SearchEngine candidate = searchEngineMapper.selectOne(Wrappers.<SearchEngine>lambdaQuery()
                .eq(SearchEngine::getVisible, true)
                .orderByAsc(SearchEngine::getSortOrder, SearchEngine::getId)
                .last("LIMIT 1"));
        if (candidate != null) makeDefault(candidate);
    }

    private void makeDefault(SearchEngine engine) {
        LocalDateTime now = LocalDateTime.now();
        searchEngineMapper.update(null, Wrappers.<SearchEngine>lambdaUpdate()
                .eq(SearchEngine::getDefaultEngine, true)
                .ne(SearchEngine::getId, engine.getId())
                .set(SearchEngine::getDefaultEngine, false)
                .set(SearchEngine::getUpdatedAt, now));
        engine.setDefaultEngine(true);
        engine.setVisible(true);
        engine.setUpdatedAt(now);
        searchEngineMapper.updateById(engine);
    }

    private void lockAll() {
        searchEngineMapper.lockAllIds();
    }

    private SearchEngineVO toVO(SearchEngine engine) {
        return new SearchEngineVO(
                engine.getId(), engine.getName(), emptyIfNull(engine.getIcon()), engine.getSearchUrl(),
                emptyIfNull(engine.getPlaceholder()), Boolean.TRUE.equals(engine.getDefaultEngine()),
                engine.getSortOrder(), Boolean.TRUE.equals(engine.getVisible())
        );
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
