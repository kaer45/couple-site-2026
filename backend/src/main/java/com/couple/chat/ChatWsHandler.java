package com.couple.chat;

import com.couple.chat.agent.AgentChatService;
import com.couple.chat.dto.MessageRequest;
import com.couple.chat.dto.MessageVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 聊天 WebSocket 处理器（鉴权在 ChatHandshakeInterceptor 完成）
 *
 * - 广播：按 coupleId 分组保存 session，来消息推给同 couple 全部连接（含发送者，前端统一渲染）
 * - 心跳：定时清理 90s 无消息的死连接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWsHandler extends TextWebSocketHandler {

    /** 心跳阈值：90s 无活跃消息的连接视为死连接 */
    private static final long HEARTBEAT_TIMEOUT_MS = 90_000L;

    private final ChatService chatService;
    private final AgentChatService agentChatService;
    private final ObjectMapper objectMapper;

    /** coupleId → 该 couple 的所有在线 session */
    private final Map<Long, Set<WebSocketSession>> coupleSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long coupleId = attr(session, ChatHandshakeInterceptor.ATTR_COUPLE_ID);
        coupleSessions.computeIfAbsent(coupleId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("聊天连接建立 userId={}, coupleId={}, 该情侣在线连接数: {}",
                attr(session, ChatHandshakeInterceptor.ATTR_USER_ID), coupleId, coupleSessions.get(coupleId).size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        tick(session);
        try {
            MessageRequest request = objectMapper.readValue(message.getPayload(), MessageRequest.class);
            Long senderId = attr(session, ChatHandshakeInterceptor.ATTR_USER_ID);
            Long coupleId = attr(session, ChatHandshakeInterceptor.ATTR_COUPLE_ID);
            if (senderId == null || coupleId == null) {
                return;
            }
            // 幂等保存在 service 内完成：撞 (couple_id, client_msg_id) 唯一键返回 null，不重复广播
            boolean isBot = "BOT".equalsIgnoreCase(request.getMsgType());
            // BOT 但问题为空：不落库空消息，直接给降级提示（不调 Python）
            if (isBot && !org.springframework.util.StringUtils.hasText(request.getContent())) {
                agentChatService.respondEmptyQuestion(coupleId, senderId, json -> sendRaw(coupleId, json));
                return;
            }
            MessageVO saved = chatService.save(coupleId, senderId,
                    request.getType(), request.getContent(), request.getClientMsgId(), isBot);
            if (saved != null) {
                broadcast(coupleId, saved);
                // BOT 消息（msgType 语义判定，不再依赖 @bot 字符串前缀）：异步转 Agent，流式回复推给同 couple
                if (isBot) {
                    agentChatService.processAgentMessage(coupleId, senderId, saved.getContent(),
                            "couple", coupleId, json -> sendRaw(coupleId, json));
                }
            }
        } catch (Exception e) {
            log.warn("聊天消息解析/处理失败: {}", e.getMessage());
        }
    }

    /** @bot 触发判定：由上行 msgType=BOT 决定（@bot 前缀只存在于前端 UI 层，不参与判定） */

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("聊天传输异常: {}", exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
    }

    /**
     * 心跳：定期清理 90s 无活跃消息的连接（探测死连接/异常断开）
     */
    @Scheduled(fixedDelay = 30_000L)
    public void heartbeats() {
        long now = System.currentTimeMillis();
        coupleSessions.values().forEach(sessions ->
                sessions.removeIf(s -> {
                    Long last = (Long) s.getAttributes().get(ChatHandshakeInterceptor.ATTR_LAST_ACTIVE);
                    boolean dead = !s.isOpen() || (last != null && now - last > HEARTBEAT_TIMEOUT_MS);
                    if (dead && s.isOpen()) {
                        try {
                            s.close(new CloseStatus(4001, "heartbeat timeout"));
                        } catch (Exception ignored) {
                            // 关闭失败按死连接处理
                        }
                    }
                    return dead;
                }));
    }

    /** 广播给同 couple 的所有在线 session（含发送者） */
    public void broadcast(Long coupleId, MessageVO vo) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(vo);
        } catch (Exception e) {
            log.error("消息序列化失败", e);
            return;
        }
        sendRaw(coupleId, payload);
    }

    /** 供阶段 3（@bot/Agent 流式）复用：向同 couple 广播原始 JSON（SSE 片段/结束帧） */
    public void sendRaw(Long coupleId, String json) {
        Set<WebSocketSession> sessions = coupleSessions.get(coupleId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        sessions.removeIf(s -> {
            if (!s.isOpen()) {
                return true;
            }
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.warn("推送失败，移除连接: {}", e.getMessage());
                return true;
            }
            return false;
        });
    }

    private void removeSession(WebSocketSession session) {
        Long coupleId = attr(session, ChatHandshakeInterceptor.ATTR_COUPLE_ID);
        if (coupleId != null) {
            Set<WebSocketSession> sessions = coupleSessions.get(coupleId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    coupleSessions.remove(coupleId);
                }
            }
        }
    }

    private void tick(WebSocketSession session) {
        session.getAttributes().put(ChatHandshakeInterceptor.ATTR_LAST_ACTIVE, System.currentTimeMillis());
    }

    private Long attr(WebSocketSession session, String key) {
        Object v = session.getAttributes().get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }
}