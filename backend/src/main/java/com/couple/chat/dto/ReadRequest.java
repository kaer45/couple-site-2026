package com.couple.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 上报已读位点请求
 */
@Data
public class ReadRequest {

    /** 该用户最新已读的消息 id */
    @NotNull(message = "lastReadMessageId 不能为空")
    private Long lastReadMessageId;
}