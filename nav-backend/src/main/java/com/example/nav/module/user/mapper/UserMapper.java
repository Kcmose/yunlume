package com.example.nav.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.nav.module.user.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface UserMapper extends BaseMapper<User> {

    @Update("""
            UPDATE sys_user
            SET password = #{newPassword},
                token_version = token_version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{id}
              AND token_version = #{currentTokenVersion}
              AND password = #{currentEncodedPassword}
            """)
    int updatePasswordAndIncrementTokenVersion(
            @Param("id") Long id,
            @Param("currentTokenVersion") Integer currentTokenVersion,
            @Param("currentEncodedPassword") String currentEncodedPassword,
            @Param("newPassword") String newPassword,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
            UPDATE sys_user
            SET token_version = token_version + 1,
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int incrementTokenVersion(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
