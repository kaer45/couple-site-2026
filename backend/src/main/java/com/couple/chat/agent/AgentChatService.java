package com.couple.chat.agent;

import com.couple.agent.AgentChatClient;
import com.couple.chat.ChatService;
import com.couple.chat.dto.AgentStreamFrame;
import com.couple.chat.dto.MessageVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Agent 对话编排：@bot 消息 → Python /v1/chat(SSE) → 流式帧推前端 → 完整回复落库
 *
 * - pusher 回调由调用方（ChatWsHandler.sendRaw）注入，避免 Handler ↔ Service 循环依赖
 * - scope/session 规则：聊天= couple{id}；/ai 页= user{id}（阶段 5 复用本类）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final AgentChatClient agentChatClient;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /**
     * 处理一条 @bot 触发的消息并流式回复（异步执行）
     */
    @Async
    public void processAgentMessage(Long coupleId, Long userId, String userMessage,
                                    String scope, Long scopeId, Consumer<String> pusher) {
        String sessionId = "couple".equals(scope) ? "couple" + scopeId
                : "user" + scopeId;
        String agentMsgId = UUID.randomUUID().toString();

        AgentChatClient.ChatCallResult result =
                agentChatClient.call(userId, coupleId, sessionId, userMessage, scope, scopeId);

        if (!result.isOk()) {
            // 失败：三类降级文案 + 错误结束帧；完整文案落库便于历史回看
            String fallback = fallbackText(result.getErrorType());
            MessageVO vo = chatService.save(coupleId, userId, "AGENT", fallback, agentMsgId, true);
            AgentStreamFrame frame = new AgentStreamFrame();
            frame.setClientMsgId(agentMsgId);
            frame.setFinalFrame(true);
            frame.setMessageId(vo != null ? vo.getId() : null);
            frame.setError(fallback);
            push(pusher, frame);
            return;
        }

        // 成功：逐段推流式帧
        for (String delta : result.getDeltas()) {
            AgentStreamFrame frame = new AgentStreamFrame();
            frame.setClientMsgId(agentMsgId);
            frame.setDelta(delta);
            frame.setFinalFrame(false);
            push(pusher, frame);
        }
        // 完整回复落库（历史/重连后可见）
        MessageVO vo = chatService.save(coupleId, userId, "AGENT",
                result.fullText(), agentMsgId, true);
        AgentStreamFrame done = new AgentStreamFrame();
        done.setClientMsgId(agentMsgId);
        done.setFinalFrame(true);
        done.setMessageId(vo != null ? vo.getId() : null);
        push(pusher, done);
        log.info("Agent 回复完成 coupleId={}, userId={}, 长度={}", coupleId, userId, result.fullText().length());
    }

    /** 四态降级文案（Controller /ai 页也复用） */
    public static String fallbackText(String errorType) {
        return switch (errorType == null ? "" : errorType) {
            case "DISABLED" -> "AI 助手尚未启用，请稍后再试。";
            case "CIRCUIT_OPEN", "UNREACHABLE" -> "AI 助手服务暂不可用，请稍后再试。";
            case "TIMEOUT" -> "AI 助手响应超时，请稍后重试。";
            default -> "AI 助手服务繁忙，请稍后重试。";
        };
    }

    private void push(Consumer<String> pusher, AgentStreamFrame frame) {
        try {
            pusher.accept(objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.warn("流式帧序列化失败: {}", e.getMessage());
        }
    }
}