package com.couple.album.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建相册请求
 */
@Data
public class AlbumRequest {

    @NotBlank(message = "相册名称不能为空")
    private String name;

    private String description;
}
