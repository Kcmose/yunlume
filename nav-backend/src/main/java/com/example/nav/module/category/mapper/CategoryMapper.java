package com.example.nav.module.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.nav.module.category.entity.Category;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface CategoryMapper extends BaseMapper<Category> {

    @Update("""
            UPDATE nav_category
            SET sort_order = #{sortOrder}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateSortOrder(
            @Param("id") Long id,
            @Param("sortOrder") Integer sortOrder,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
