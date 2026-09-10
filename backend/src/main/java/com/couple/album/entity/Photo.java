package com.couple.album.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 照片表实体
 */
@Data
@TableName("photo")
public class Photo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属相册 ID */
    @TableField("album_id")
    private Long albumId;

    /** 上传者用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 原图 URL */
    private String url;

    /** 缩略图 URL（可选） */
    @TableField("thumbnail_url")
    private String thumbnailUrl;

    /** 照片描述 */
    private String description;

    /** 拍摄时间（可选） */
    @TableField("taken_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime takenAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
