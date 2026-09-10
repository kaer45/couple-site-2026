package com.couple.moment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发布动态请求
 */
@Data
public class MomentRequest {

    /** 文字内容：必填，≤2000 字 */
    @NotBlank(message = "内容不能为空")
    @Size(max = 2000, message = "内容不能超过2000字")
    private String content;

    /** 图片 URL 数组（可空） */
    private List<String> images;

    /** 定位/地点（可空） */
    private String location;
}
