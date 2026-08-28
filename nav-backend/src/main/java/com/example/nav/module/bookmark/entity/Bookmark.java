package com.example.nav.module.bookmark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("nav_bookmark")
public class Bookmark {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long categoryId;
    private String name;
    private String url;
    private String icon;
    private String description;
    private Integer sortOrder;
    @TableField("is_recommend")
    private Boolean recommend;
    @TableField("is_external")
    private Boolean external;
    private Boolean visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
