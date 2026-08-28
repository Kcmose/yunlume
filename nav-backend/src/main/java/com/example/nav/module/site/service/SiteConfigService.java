package com.example.nav.module.site.service;

import com.example.nav.module.site.dto.SiteConfigUpdateDTO;
import com.example.nav.module.site.vo.SiteConfigVO;

public interface SiteConfigService {

    SiteConfigVO getConfig();

    SiteConfigVO update(SiteConfigUpdateDTO updateDTO);
}
