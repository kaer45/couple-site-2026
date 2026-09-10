package com.couple.album.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 相册表实体
 */
@Data
@TableName("album")
public class Album {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属情侣关系 ID */
    @TableField("couple_id")
    private Long coupleId;

    /** 相册名称 */
    private String name;

    /** 相册描述 */
    private String description;

    /** 封面图 URL（取最新照片） */
    @TableField("cover_url")
    private String coverUrl;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
