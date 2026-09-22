package com.couple.chat.dto;

import lombok.Data;

/**
 * 聊天消息上行请求（前端 → 服务端）
 * type: TEXT(默认) / IMAGE；AGENT 由服务端生成，不进此结构
 */
@Data
public class MessageRequest {

    /** TXT 文本 / IMAGE 图片 */
    private String type;

    /** 文本内容或图片 URL */
    private String content;

    /** 客户端幂等键（UUID），重连重发同一消息保持不变 */
    private String clientMsgId;
}