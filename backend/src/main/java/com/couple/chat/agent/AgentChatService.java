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
        // 累积"已经推给前端的内容"：中途失败时以它为准落库，保证历史记录与用户看到的一致
        StringBuilder streamed = new StringBuilder();

        AgentChatClient.ChatCallResult result =
                agentChatClient.call(userId, coupleId, sessionId, userMessage, scope, scopeId,
                        delta -> {
                            streamed.append(delta);
                            pushDelta(pusher, agentMsgId, delta);   // 边收边推，前端逐字显示
                        });

        if (!result.isOk()) {
            // 失败：已推过的部分优先落库；一个字都没产出时才用降级文案，并作为错误结束帧下发
            boolean nothingStreamed = streamed.length() == 0;
            String text = nothingStreamed ? fallbackText(result.getErrorType()) : streamed.toString();
            MessageVO vo = chatService.save(coupleId, userId, "AGENT", text, agentMsgId, true);
            pushFinal(pusher, agentMsgId, vo, nothingStreamed ? text : null);
            log.warn("Agent 回复失败 coupleId={}, userId={}, 已推长度={}, 原因={}",
                    coupleId, userId, streamed.length(), result.getErrorType());
            return;
        }

        // 成功：完整回复落库（历史/重连后可见）
        MessageVO vo = chatService.save(coupleId, userId, "AGENT",
                result.fullText(), agentMsgId, true);
        pushFinal(pusher, agentMsgId, vo, null);
        log.info("Agent 回复完成 coupleId={}, userId={}, 长度={}", coupleId, userId, result.fullText().length());
    }

    /** BOT 触发但问题为空：不调 Python，直接落库一条降级提示并下发结束帧 */
    public void respondEmptyQuestion(Long coupleId, Long userId, Consumer<String> pusher) {
        String text = "我还没收到你的问题哦～ 点 @bot 后输入你想问的内容吧。";
        String agentMsgId = UUID.randomUUID().toString();
        MessageVO vo = chatService.save(coupleId, userId, "AGENT", text, agentMsgId, true);
        pushFinal(pusher, agentMsgId, vo, null);
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

    /** 增量帧：前端按 clientMsgId 找到同一气泡追加文本 */
    private void pushDelta(Consumer<String> pusher, String agentMsgId, String delta) {
        AgentStreamFrame frame = new AgentStreamFrame();
        frame.setClientMsgId(agentMsgId);
        frame.setDelta(delta);
        frame.setFinalFrame(false);
        push(pusher, frame);
    }

    /** 结束帧：带回真实 messageId，告诉前端本轮流结束 */
    private void pushFinal(Consumer<String> pusher, String agentMsgId, MessageVO vo, String error) {
        AgentStreamFrame frame = new AgentStreamFrame();
        frame.setClientMsgId(agentMsgId);
        frame.setFinalFrame(true);
        frame.setMessageId(vo != null ? vo.getId() : null);
        frame.setError(error);
        push(pusher, frame);
    }

    private void push(Consumer<String> pusher, AgentStreamFrame frame) {
        try {
            pusher.accept(objectMapper.writeValueAsString(frame));
        } catch (Exception e) {
            log.warn("流式帧序列化失败: {}", e.getMessage());
        }
    }
}