package com.couple.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Agent 回复流式帧（WS 下行，前端按 clientMsgId 匹配同气泡追加）
 * - 普通流：{type=AGENT_STREAM, clientMsgId, delta, finalFrame=false}，可多次
 * - 结束：   {type=AGENT_STREAM, clientMsgId, finalFrame=true, messageId}（正常）
 * - 错误：   {type=AGENT_STREAM, clientMsgId, finalFrame=true, error}（本轮无内容时才有 error）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentStreamFrame {

    public static final String TYPE = "AGENT_STREAM";

    private String type = TYPE;

    /** 服务端生成的该轮回复唯一标识（前端匹配气泡） */
    private String clientMsgId;

    /** 增量文本 */
    private String delta;

    /** true=流结束（正常或错误） */
    private Boolean finalFrame;

    /** 正常结束后落库的消息 id */
    private Long messageId;

    /** 错误文案（finalFrame=true 时可能有） */
    private String error;
}