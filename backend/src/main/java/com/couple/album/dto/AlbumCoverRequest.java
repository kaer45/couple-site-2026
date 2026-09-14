package com.couple.album.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 设置相册封面请求
 * 封面图先经 /api/files/upload 上传拿到 URL，再调用本接口设为手动封面
 */
@Data
public class AlbumCoverRequest {

    /** 封面图 URL（必填） */
    @NotBlank(message = "coverUrl 不能为空")
    private String coverUrl;
}