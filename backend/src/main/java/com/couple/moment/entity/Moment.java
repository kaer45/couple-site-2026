package com.couple.moment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动态表实体（images 为 JSON 数组，如 ["/uploads/a.jpg"]）
 */
@Data
@TableName(value = "moment", autoResultMap = true)
public class Moment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属情侣关系 ID */
    @TableField("couple_id")
    private Long coupleId;

    /** 发布者用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 文字内容 */
    private String content;

    /** 图片 URL 数组（JSON 列） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 定位/地点 */
    private String location;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
