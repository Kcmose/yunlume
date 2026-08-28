package com.example.nav.module.customlink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("custom_link")
public class CustomLink {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String url;
    private String position;
    private Integer sortOrder;
    private Boolean visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
