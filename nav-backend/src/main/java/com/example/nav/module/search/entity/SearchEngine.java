package com.example.nav.module.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("search_engine")
public class SearchEngine {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String icon;
    private String searchUrl;
    private String placeholder;
    @TableField("is_default")
    private Boolean defaultEngine;
    private Integer sortOrder;
    private Boolean visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
