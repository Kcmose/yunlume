package com.example.nav.module.customlink.service;

import com.example.nav.common.dto.SortItemDTO;
import com.example.nav.module.customlink.dto.CustomLinkDTO;
import com.example.nav.module.customlink.vo.CustomLinkVO;

import java.util.List;

public interface CustomLinkService {

    List<CustomLinkVO> listAll();

    List<CustomLinkVO> listPublic();

    CustomLinkVO create(CustomLinkDTO dto);

    CustomLinkVO update(Long id, CustomLinkDTO dto);

    void delete(Long id);

    CustomLinkVO setVisible(Long id, boolean visible);

    List<CustomLinkVO> sort(List<SortItemDTO> items);
}
