package com.couple.moment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动态视图对象
 */
@Data
public class MomentVO {

    private Long id;

    private Long userId;

    /** 发布者昵称 */
    private String nickname;

    /** 发布者头像 */
    private String avatar;

    private String content;

    private List<String> images;

    private String location;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
