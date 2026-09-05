package com.example.nav.module.bookmark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.nav.module.bookmark.entity.Bookmark;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface BookmarkMapper extends BaseMapper<Bookmark> {

    @Update("""
            UPDATE nav_bookmark
            SET visible = #{visible}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateVisible(
            @Param("id") Long id,
            @Param("visible") boolean visible,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
            UPDATE nav_bookmark
            SET sort_order = #{sortOrder}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateSortOrder(
            @Param("id") Long id,
            @Param("sortOrder") Integer sortOrder,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
            UPDATE nav_bookmark
            SET category_id = #{categoryId}, sort_order = #{sortOrder}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int moveToCategory(
            @Param("id") Long id,
            @Param("categoryId") Long categoryId,
            @Param("sortOrder") Integer sortOrder,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
