package com.example.nav.module.customlink.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.common.exception.BusinessException;
import com.example.nav.module.customlink.dto.CustomLinkDTO;
import com.example.nav.module.customlink.entity.CustomLink;
import com.example.nav.module.customlink.mapper.CustomLinkMapper;
import com.example.nav.module.customlink.service.CustomLinkService;
import com.example.nav.module.customlink.vo.CustomLinkVO;
import com.example.nav.module.publicdata.PublicDataCacheNames;
import com.example.nav.module.publicdata.PublicDataCacheInvalidator;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CustomLinkServiceImpl implements CustomLinkService {

    private static final String HEADER = "header";
    private static final String FOOTER = "footer";
    private static final Set<String> ALLOWED_POSITIONS = Set.of(HEADER, FOOTER);
    private static final Comparator<CustomLink> DISPLAY_ORDER = Comparator
            .comparingInt((CustomLink link) -> positionRank(link.getPosition()))
            .thenComparing(CustomLink::getSortOrder, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(CustomLink::getId, Comparator.nullsLast(Long::compareTo));

    private final CustomLinkMapper customLinkMapper;
    private final PublicDataCacheInvalidator cacheInvalidator;

    public CustomLinkServiceImpl(
            CustomLinkMapper customLinkMapper,
            PublicDataCacheInvalidator cacheInvalidator
    ) {
        this.customLinkMapper = customLinkMapper;
        this.cacheInvalidator = cacheInvalidator;
    }

    @Override
    public List<CustomLinkVO> listAll() {
        return customLinkMapper.selectList(null).stream()
                .sorted(DISPLAY_ORDER)
                .map(this::toVO)
                .toList();
    }

    @Override
    public List<CustomLinkVO> listPublic() {
        return customLinkMapper.selectList(Wrappers.<CustomLink>lambdaQuery()
                        .eq(CustomLink::getVisible, true)
                        .in(CustomLink::getPosition, HEADER, FOOTER))
                .stream()
                .sorted(DISPLAY_ORDER)
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional
    public CustomLinkVO create(CustomLinkDTO dto) {
        NormalizedLink normalized = normalize(dto);
        LocalDateTime now = LocalDateTime.now();
        CustomLink link = new CustomLink();
        apply(link, normalized);
        link.setSortOrder(dto.sortOrder() == null
                ? nextSortOrder(normalized.position())
                : dto.sortOrder());
        link.setVisible(dto.visible() == null || dto.visible());
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        customLinkMapper.insert(link);
        invalidatePublicCustomLinks();
        return toVO(link);
    }

    @Override
    @Transactional
    public CustomLinkVO update(Long id, CustomLinkDTO dto) {
        CustomLink link = requireLink(id);
        NormalizedLink normalized = normalize(dto);
        boolean movesToAnotherPosition = !normalized.position().equals(link.getPosition());

        apply(link, normalized);
        if (dto.sortOrder() != null) {
            link.setSortOrder(dto.sortOrder());
        } else if (movesToAnotherPosition) {
            link.setSortOrder(nextSortOrder(normalized.position()));
        }
        if (dto.visible() != null) link.setVisible(dto.visible());
        link.setUpdatedAt(LocalDateTime.now());
        customLinkMapper.updateById(link);
        invalidatePublicCustomLinks();
        return toVO(link);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireLink(id);
        customLinkMapper.deleteById(id);
        invalidatePublicCustomLinks();
    }

    @Override
    @Transactional
    public CustomLinkVO setVisible(Long id, boolean visible) {
        if (customLinkMapper.updateVisible(id, visible, LocalDateTime.now()) != 1) {
            throw BusinessException.notFound("自定义链接不存在");
        }
        invalidatePublicCustomLinks();
        return toVO(requireLink(id));
    }

    @Override
    @Transactional
    public List<CustomLinkVO> sort(List<SortItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("排序列表不能为空");
        }

        Set<Long> ids = new HashSet<>();
        for (SortItemDTO item : items) {
            if (!ids.add(item.id())) {
                throw BusinessException.badRequest("排序列表包含重复 ID");
            }
        }

        Map<Long, CustomLink> linksById = new HashMap<>();
        customLinkMapper.selectByIds(ids).forEach(link -> linksById.put(link.getId(), link));
        for (SortItemDTO item : items) {
            if (!linksById.containsKey(item.id())) {
                throw BusinessException.notFound("自定义链接不存在");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (SortItemDTO item : items) {
            if (customLinkMapper.updateSortOrder(item.id(), item.sortOrder(), now) != 1) {
                throw BusinessException.conflict("自定义链接状态已变化，请刷新后重试");
            }
        }
        invalidatePublicCustomLinks();
        return listAll();
    }

    private void invalidatePublicCustomLinks() {
        cacheInvalidator.invalidate(PublicDataCacheNames.CUSTOM_LINKS);
    }

    private NormalizedLink normalize(CustomLinkDTO dto) {
        String position = dto.position().trim();
        if (!ALLOWED_POSITIONS.contains(position)) {
            throw BusinessException.badRequest("显示位置只能是 header 或 footer");
        }
        return new NormalizedLink(dto.title().trim(), validateUrl(dto.url()), position);
    }

    private String validateUrl(String value) {
        String url = value.trim();
        if (url.indexOf('\\') >= 0 || url.codePoints().anyMatch(this::isWhitespaceOrControl)) {
            throw BusinessException.badRequest("链接地址不能包含空白、控制字符或反斜杠");
        }

        try {
            URI uri = URI.create(url);
            if (url.startsWith("#")) {
                boolean validAnchor = uri.getScheme() == null
                        && uri.getRawAuthority() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && uri.getRawQuery() == null
                        && uri.getRawFragment() != null
                        && !uri.getRawFragment().isBlank();
                if (!validAnchor) throw invalidUrl();
                return url;
            }

            if (url.startsWith("/")) {
                boolean validInternalPath = !url.startsWith("//")
                        && !uri.isAbsolute()
                        && uri.getRawAuthority() == null
                        && uri.getRawPath() != null
                        && uri.getRawPath().startsWith("/");
                if (!validInternalPath) throw invalidUrl();
                return url;
            }

            boolean validScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (!validScheme || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                throw invalidUrl();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
    }

    private boolean isWhitespaceOrControl(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isISOControl(codePoint);
    }

    private BusinessException invalidUrl() {
        return BusinessException.badRequest("链接地址必须是安全的 HTTP(S) 地址、站内路径或锚点");
    }

    private void apply(CustomLink link, NormalizedLink normalized) {
        link.setTitle(normalized.title());
        link.setUrl(normalized.url());
        link.setPosition(normalized.position());
    }

    private int nextSortOrder(String position) {
        CustomLink last = customLinkMapper.selectOne(Wrappers.<CustomLink>lambdaQuery()
                .eq(CustomLink::getPosition, position)
                .orderByDesc(CustomLink::getSortOrder)
                .orderByDesc(CustomLink::getId)
                .last("LIMIT 1"));
        if (last == null || last.getSortOrder() == null) return 0;
        long next = (long) last.getSortOrder() + 10;
        if (next > Integer.MAX_VALUE) {
            throw BusinessException.conflict("自定义链接排序值已达到上限，请先调整排序");
        }
        return (int) Math.max(0L, next);
    }

    private CustomLink requireLink(Long id) {
        CustomLink link = customLinkMapper.selectById(id);
        if (link == null) throw BusinessException.notFound("自定义链接不存在");
        return link;
    }

    private CustomLinkVO toVO(CustomLink link) {
        return new CustomLinkVO(
                link.getId(), link.getTitle(), link.getUrl(), link.getPosition(), link.getSortOrder(),
                Boolean.TRUE.equals(link.getVisible()), link.getCreatedAt(), link.getUpdatedAt()
        );
    }

    private static int positionRank(String position) {
        if (HEADER.equals(position)) return 0;
        if (FOOTER.equals(position)) return 1;
        return 2;
    }

    private record NormalizedLink(String title, String url, String position) {
    }
}
