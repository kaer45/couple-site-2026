package com.couple.chat.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * /ai 页对话请求（个人域：scope=personal）
 */
@Data
public class AgentChatRequest {

    @NotBlank(message = "消息不能为空")
    private String message;
}