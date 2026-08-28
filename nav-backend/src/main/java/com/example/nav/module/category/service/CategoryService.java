package com.example.nav.module.category.service;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.module.category.dto.CategoryCreateDTO;
import com.example.nav.module.category.dto.CategoryUpdateDTO;
import com.example.nav.module.category.vo.CategoryVO;

import java.util.List;

public interface CategoryService {

    List<CategoryVO> listAll();

    List<CategoryVO> listVisible();

    CategoryVO create(CategoryCreateDTO createDTO);

    CategoryVO update(Long id, CategoryUpdateDTO updateDTO);

    void delete(Long id);

    CategoryVO setVisible(Long id, boolean visible);

    List<CategoryVO> sort(List<SortItemDTO> items);
}
