package com.example.nav.module.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.nav.module.search.entity.SearchEngine;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SearchEngineMapper extends BaseMapper<SearchEngine> {

    @Select("SELECT id FROM search_engine ORDER BY id FOR UPDATE")
    List<Long> lockAllIds();
}
