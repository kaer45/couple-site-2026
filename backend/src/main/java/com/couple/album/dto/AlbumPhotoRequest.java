package com.couple.album.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 上传照片请求（JSON 请求体）
 * 照片先经 /api/files/upload 逐张上传拿 URL，再统一绑定到相册
 */
@Data
public class AlbumPhotoRequest {

    /** 照片 URL 列表（必填非空） */
    @NotEmpty(message = "urls 不能为空")
    private List<@NotBlank(message = "照片 URL 不能为空") String> urls;

    /** 照片描述（可选） */
    private String description;
}
