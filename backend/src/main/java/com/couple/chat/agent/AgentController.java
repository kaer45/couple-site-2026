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
                AgentChatClient.ChatCallResult result = agentChatClient.call(
                        userId, coupleId, "user" + userId, request.getMessage(),
                        "personal", userId);
                if (!result.isOk()) {
                    emitter.send(SseEmitter.event().data(Map.of("error", AgentChatService.fallbackText(result.getErrorType()))));
                } else {
                    for (String delta : result.getDeltas()) {
                        emitter.send(SseEmitter.event().data(Map.of("delta", delta)));
                    }
                    if (!result.getRefs().isEmpty()) {
                        emitter.send(SseEmitter.event().data(Map.of("refs", result.getRefs())));
                    }
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                log.warn("/ai SSE 应答中断: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}