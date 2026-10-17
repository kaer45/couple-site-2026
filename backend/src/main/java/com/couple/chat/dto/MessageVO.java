package com.couple.chat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息视图对象（WS 下行 / REST 返回共用）
 */
@Data
public class MessageVO {

    private Long id;

    private Long senderId;

    /** TEXT / IMAGE / AGENT */
    private String type;

    private String content;

    /** 幂等键（前端据此给 AGENT 流式消息匹配同一气泡） */
    private String clientMsgId;

    private Boolean botFlag;

    /** 消息语义类型：USER / BOT / AGENT（历史拉取时由 type+botFlag 推导还原） */
    private String msgType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}