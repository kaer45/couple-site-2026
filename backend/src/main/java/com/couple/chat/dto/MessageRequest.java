package com.couple.chat.dto;

import lombok.Data;

/**
 * 聊天消息上行请求（前端 → 服务端）
 * type: TEXT(默认) / IMAGE；AGENT 由服务端生成，不进此结构
 * msgType: USER(默认) / BOT；缺省按 USER 兜底（兼容旧客户端）
 */
@Data
public class MessageRequest {

    /** TEXT 文本 / IMAGE 图片 */
    private String type;

    /** 文本内容或图片 URL（BOT 消息为纯问题文本，不含 @bot 前缀） */
    private String content;

    /** 客户端幂等键（UUID），重连重发同一消息保持不变 */
    private String clientMsgId;

    /** 消息语义类型：USER=普通聊天 / BOT=发给 AI 助手的问题；缺省按 USER */
    private String msgType;
}