package com.couple.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 * 幂等：UNIQUE(couple_id, client_msg_id)，前端重发同一条 clientMsgId 会撞唯一键被丢弃
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属情侣关系ID（聊天空间域） */
    @TableField("couple_id")
    private Long coupleId;

    /** 发送者用户ID */
    @TableField("sender_id")
    private Long senderId;

    /** 消息类型：TEXT / IMAGE / AGENT */
    private String type;

    /** 消息内容（文本或图片 URL） */
    private String content;

    /** 是否 @bot 触发（1=Agent 相关） */
    @TableField("bot_flag")
    private Boolean botFlag;

    /** 客户端幂等键（服务端生成的 AGENT 消息为 null） */
    @TableField("client_msg_id")
    private String clientMsgId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}