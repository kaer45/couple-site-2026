package com.couple.anniversary.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知表实体
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人用户ID */
    @TableField("user_id")
    private Long userId;

    /** 所属情侣关系ID */
    @TableField("couple_id")
    private Long coupleId;

    /** 通知类型，如 ANNIVERSARY_REMIND */
    private String type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 关联业务ID（如纪念日ID） */
    @TableField("related_id")
    private Long relatedId;

    /** 是否已读（0=未读，1=已读） */
    @TableField("is_read")
    private Boolean isRead;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("created_at")
    private LocalDateTime createdAt;
}