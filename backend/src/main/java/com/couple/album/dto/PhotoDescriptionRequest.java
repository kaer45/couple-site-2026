package com.couple.album.dto;

import lombok.Data;

/**
 * 更新照片描述请求
 */
@Data
public class PhotoDescriptionRequest {

    /** 照片描述（可空=清空） */
    private String description;
}