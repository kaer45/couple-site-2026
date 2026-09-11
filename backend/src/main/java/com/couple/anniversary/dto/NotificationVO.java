package com.couple.anniversary.dto;

import com.couple.anniversary.entity.Notification;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知读取 VO：对前端暴露的字段，不含内部 userId/coupleId/relatedId
 */
@Data
public class NotificationVO {

    private Long id;

    /** 通知类型，如 ANNIVERSARY_REMIND */
    private String type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 是否已读（0=未读，1=已读） */
    private Boolean isRead;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    public static NotificationVO of(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setIsRead(n.getIsRead());
        vo.setCreatedAt(n.getCreatedAt());
        return vo;
    }
}