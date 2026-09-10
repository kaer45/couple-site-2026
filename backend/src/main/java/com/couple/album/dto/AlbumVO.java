package com.couple.album.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 相册列表/创建响应
 */
@Data
public class AlbumVO {

    private Long id;

    private String name;

    private String description;

    /** 封面 URL（最新一张照片，无照片则 null） */
    private String coverUrl;

    /** 照片数量 */
    private Long photoCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
