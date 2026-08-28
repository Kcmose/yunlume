package com.example.nav.module.site.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.nav.module.site.entity.SiteConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SiteConfigMapper extends BaseMapper<SiteConfig> {

    @Select("SELECT COUNT(*) FROM site_config WHERE install_completed_at IS NOT NULL")
    long countCompletedInstallations();

    @Select("SELECT * FROM site_config ORDER BY id FOR UPDATE")
    List<SiteConfig> selectAllForUpdate();

    /**
     * Reads the identity of the row already locked by {@link #selectAllForUpdate()}.
     * PostgreSQL's UUID value is cast to text deliberately: relying on implicit
     * UUID-to-entity conversion made a valid fresh installation look like an
     * instance switch on the real PostgreSQL driver.
     */
    @Select("SELECT install_instance_id::text FROM site_config WHERE id = #{id}")
    String selectInstallInstanceIdText(@Param("id") Long id);

    @Update("""
            UPDATE site_config
            SET site_name = #{siteName},
                site_description = #{siteDescription},
                install_completed_at = #{completedAt},
                version = version + 1,
                updated_at = #{completedAt}
            WHERE id = #{id}
              AND install_completed_at IS NULL
              AND (#{expectedInstanceId} IS NULL
                   OR install_instance_id = #{expectedInstanceId})
            """)
    int completeInstallation(
            @Param("id") Long id,
            @Param("siteName") String siteName,
            @Param("siteDescription") String siteDescription,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("expectedInstanceId") java.util.UUID expectedInstanceId
    );

    @Update("""
            UPDATE site_config
            SET install_completed_at = #{completedAt},
                updated_at = #{completedAt}
            WHERE install_completed_at IS NULL
              AND EXISTS (SELECT 1 FROM sys_user)
            """)
    int markInstallationCompletedWhenUserExists(@Param("completedAt") LocalDateTime completedAt);
}
