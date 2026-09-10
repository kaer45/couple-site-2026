package com.couple.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新头像请求（契约 1.5）
 */
@Data
public class AvatarRequest {

    /** 头像图片 URL（先经 /api/files/upload 上传得到） */
    @NotBlank(message = "头像地址不能为空")
    private String avatarUrl;
}
