package com.couple.album.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 相册详情响应（含照片列表）
 */
@Data
public class AlbumDetailVO {

    private Long id;

    private String name;

    private String description;

    private String coverUrl;

    private List<PhotoVO> photos;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
