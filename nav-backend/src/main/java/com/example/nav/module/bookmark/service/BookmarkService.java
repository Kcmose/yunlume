package com.example.nav.module.bookmark.service;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.module.bookmark.dto.BookmarkBatchMoveDTO;
import com.example.nav.module.bookmark.dto.BookmarkCreateDTO;
import com.example.nav.module.bookmark.dto.BookmarkUpdateDTO;
import com.example.nav.module.bookmark.vo.BookmarkVO;

import java.util.List;

public interface BookmarkService {

    List<BookmarkVO> list(Long categoryId);

    List<BookmarkVO> listVisible(Long categoryId);

    List<BookmarkVO> listVisibleByCategoryIds(List<Long> categoryIds);

    BookmarkVO create(BookmarkCreateDTO createDTO);

    BookmarkVO update(Long id, BookmarkUpdateDTO updateDTO);

    void delete(Long id);

    BookmarkVO setVisible(Long id, boolean visible);

    List<BookmarkVO> batchMove(BookmarkBatchMoveDTO moveDTO);

    List<BookmarkVO> sort(List<SortItemDTO> items);
}
