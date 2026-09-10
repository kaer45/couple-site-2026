package com.couple.album.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 照片视图对象
 */
@Data
public class PhotoVO {

    private Long id;

    /** 上传者用户ID（前端据此判断是否自己上传，控制删除按钮） */
    private Long userId;

    private String url;

    private String thumbnailUrl;

    private String description;

    /** 上传者昵称 */
    private String uploaderNickname;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
