package com.example.nav.module.publicdata.vo;

import com.example.nav.module.bookmark.vo.BookmarkVO;

import java.io.Serializable;
import java.util.List;

public record NavigationVO(
        Long id,
        String name,
        String icon,
        Integer sortOrder,
        Boolean visible,
        List<BookmarkVO> bookmarks
) implements Serializable {
}
