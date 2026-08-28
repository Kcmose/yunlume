package com.example.nav.module.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("nav_category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String icon;
    private Integer sortOrder;
    private Boolean visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
