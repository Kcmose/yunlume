package com.example.nav.module.customlink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.nav.module.customlink.entity.CustomLink;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface CustomLinkMapper extends BaseMapper<CustomLink> {

    @Update("""
            UPDATE custom_link
            SET visible = #{visible}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateVisible(
            @Param("id") Long id,
            @Param("visible") boolean visible,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
            UPDATE custom_link
            SET sort_order = #{sortOrder}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateSortOrder(
            @Param("id") Long id,
            @Param("sortOrder") Integer sortOrder,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
