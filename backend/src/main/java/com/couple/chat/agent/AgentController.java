package com.couple.chat.agent;

import com.couple.agent.AgentChatClient;
import com.couple.common.BusinessException;
import com.couple.config.CoupleUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * AI 助手对话接口（独立 /ai 页，个人私有域）
 * 返回 SSE 流：data: {"delta":"增量"} / data: {"refs":[...]} / data: {"error":"..."} / data: [DONE]
 * 与聊天内 @bot 共用 AgentChatClient，仅 scope/session 不同（personal / user{id}）
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentChatClient agentChatClient;
    private final Executor agentExecutor;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@AuthenticationPrincipal CoupleUserPrincipal principal,
                           @Valid @RequestBody AgentChatRequest request) {
        if (principal == null) {
            throw new BusinessException(401, "未登录");
        }
        Long userId = principal.id();
        Long coupleId = principal.coupleId();

        SseEmitter emitter = new SseEmitter(120_000L);
        agentExecutor.execute(() -> {
            try {
                // 真流式：每收到一段增量就立刻 send，浏览器端即可逐字显示
                AgentChatClient.ChatCallResult result = agentChatClient.call(
                        userId, coupleId, "user" + userId, request.getMessage(),
                        "personal", userId,
                        delta -> send(emitter, Map.of("delta", delta)));

                if (!result.isOk()) {
                    // 失败：补一条 error 事件（前端仅在正文为空时展示；已推过内容则保留已有内容）
                    send(emitter, Map.of("error", AgentChatService.fallbackText(result.getErrorType())));
                } else if (!result.getRefs().isEmpty()) {
                    send(emitter, Map.of("refs", result.getRefs()));
                }
                send(emitter, "[DONE]");
                emitter.complete();
            } catch (Exception e) {
                log.warn("/ai SSE 应答中断: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 下发一个 SSE 事件；客户端中途断开会让 send 抛异常，吞掉即可，避免打断整条流 */
    private void send(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (Exception e) {
            log.debug("/ai SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}