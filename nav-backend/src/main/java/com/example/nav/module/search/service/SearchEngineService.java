package com.example.nav.module.search.service;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.module.search.dto.SearchEngineDTO;
import com.example.nav.module.search.vo.SearchEngineVO;

import java.util.List;

public interface SearchEngineService {

    List<SearchEngineVO> listAll();

    List<SearchEngineVO> listPublic();

    SearchEngineVO create(SearchEngineDTO dto);

    SearchEngineVO update(Long id, SearchEngineDTO dto);

    void delete(Long id);

    SearchEngineVO setDefault(Long id);

    SearchEngineVO setVisible(Long id, boolean visible);

    List<SearchEngineVO> sort(List<SortItemDTO> items);
}
