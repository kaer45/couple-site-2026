package com.couple.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 对话客户端：消费 Python /v1/chat 的 SSE 流
 *
 * - 超时：连接 3s / 整流读取 30s
 * - 熔断：连续 5 次失败 → 30s 熔断窗口，期间直接返回 CIRCUIT_OPEN（不真正发起请求）
 * - 失败分类（决定降级文案）：DISABLED / CIRCUIT_OPEN / UNREACHABLE / TIMEOUT / FAILED
 *
 * 契约定死（Python 侧实现）：
 * POST /v1/chat   Header: X-Agent-Key
 * body: { trace_id, user_id, couple_id, session_id, message, scope, scope_id }
 * 响应：text/event-stream，data 行内容为 {"content":"增量文本","refs":[...]} 或纯文本；以 data: [DONE] 结束
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChatClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(35);

    /** 连续失败达到该次数后打开熔断 */
    private static final int FAIL_THRESHOLD = 5;
    /** 熔断窗口时长 */
    private static final long CIRCUIT_OPEN_MS = 30_000L;

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenedAtMs = 0;

    /** /v1/chat 调用结果 */
    @Data
    public static class ChatCallResult {
        private boolean ok;
        private String errorType;   // DISABLED / CIRCUIT_OPEN / UNREACHABLE / TIMEOUT / FAILED
        private String errorDetail;
        private List<String> deltas = new ArrayList<>(); // 流式增量片段
        private List<Map<String, Object>> refs = new ArrayList<>(); // 引用出处（/ai 页展示用）

        static ChatCallResult ok(String text, List<String> deltas, List<Map<String, Object>> refs) {
            ChatCallResult r = new ChatCallResult();
            r.ok = true;
            r.deltas = deltas;
            r.refs = refs;
            return r;
        }

        static ChatCallResult error(String type, String detail) {
            ChatCallResult r = new ChatCallResult();
            r.ok = false;
            r.errorType = type;
            r.errorDetail = detail;
            return r;
        }

        public String fullText() {
            return String.join("", deltas);
        }
    }

    public ChatCallResult call(Long userId, Long coupleId, String sessionId, String message,
                               String scope, Long scopeId) {
        if (!properties.isEnabled()) {
            return ChatCallResult.error("DISABLED", "agent.enabled=false");
        }
        long now = System.currentTimeMillis();
        if (circuitOpen) {
            if (now - circuitOpenedAtMs > CIRCUIT_OPEN_MS) {
                circuitOpen = false; // 半开：放一次试探
            } else {
                return ChatCallResult.error("CIRCUIT_OPEN", "熔断中");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trace_id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        body.put("user_id", userId);
        body.put("couple_id", coupleId);
        body.put("session_id", sessionId);
        body.put("message", message);
        body.put("scope", scope);
        body.put("scope_id", scopeId);

        try {
            WebClient client = buildWebClient();
            List<String> blocks = client.post()
                    .uri("/v1/chat")
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set("X-Agent-Key", properties.getKey());
                    })
                    .bodyValue(body)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(READ_TIMEOUT)
                    .collectList()
                    .block(TOTAL_TIMEOUT);

            List<String> deltas = new ArrayList<>();
            List<Map<String, Object>> refs = new ArrayList<>();
            List<String> errorHolder = new ArrayList<>(); // Python 主动上报的 error（最多一条）
            if (blocks != null) {
                for (String block : blocks) {
                    consumeData(block, deltas, refs, errorHolder);
                }
            }
            // Python 明确报错且没有产出任何内容 → 按调用失败处理（前端显示降级文案，而非空回复）
            if (!errorHolder.isEmpty() && deltas.isEmpty()) {
                if (consecutiveFailures.incrementAndGet() >= FAIL_THRESHOLD) {
                    circuitOpen = true;
                    circuitOpenedAtMs = System.currentTimeMillis();
                    log.warn("Agent 连续失败 {} 次，熔断 30s", consecutiveFailures.get());
                }
                log.warn("Agent 返回错误事件: {}", errorHolder.get(0));
                return ChatCallResult.error("FAILED", errorHolder.get(0));
            }
            consecutiveFailures.set(0);
            return ChatCallResult.ok(String.join("", deltas), deltas, refs);
        } catch (Exception e) {
            return handleFailure(e);
        }
    }

    /** 解析 data 行：JSON {content, refs, error} / 纯文本 / [DONE]
     *  兼容两种形态：Spring SSE 解码已剥离 "data:" 前缀（直接是 JSON 行），或未剥离 */
    private void consumeData(String block, List<String> deltas, List<Map<String, Object>> refs,
                             List<String> errorHolder) {
        if (block == null) {
            return;
        }
        for (String line : block.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            // Spring bodyToFlux(String) 会自动去掉 "data:" 前缀；此处兼容还带前缀的流
            String data = t.startsWith("data:") ? t.substring(5).trim() : t;
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(data);
                JsonNode content = node.get("content");
                if (content != null && !content.isNull()) {
                    deltas.add(content.asText());
                }
                JsonNode error = node.get("error");
                if (error != null && !error.isNull() && errorHolder.isEmpty()) {
                    errorHolder.add(error.asText());
                }
                JsonNode refsNode = node.get("refs");
                if (refsNode != null && refsNode.isArray()) {
                    refsNode.forEach(n -> refs.add(objectMapper.convertValue(n, Map.class)));
                }
            } catch (Exception ignore) {
                // 非 JSON：按纯文本增量处理
                deltas.add(data);
            }
        }
    }

    private ChatCallResult handleFailure(Exception e) {
        String type;
        if (e instanceof TimeoutException || e instanceof ReadTimeoutException
                || e instanceof java.util.concurrent.TimeoutException) {
            type = "TIMEOUT";
        } else if (e instanceof WebClientResponseException wce) {
            type = wce.getStatusCode().is5xxServerError() ? "FAILED" : "FAILED";
        } else {
            type = "UNREACHABLE"; // 连接失败（拒连/协议错误等）
        }
        if (consecutiveFailures.incrementAndGet() >= FAIL_THRESHOLD) {
            circuitOpen = true;
            circuitOpenedAtMs = System.currentTimeMillis();
            log.warn("Agent 连续失败 {} 次，熔断 30s", consecutiveFailures.get());
        }
        log.warn("Agent /v1/chat 调用失败 [{}]: {}", type, e.getMessage());
        return ChatCallResult.error(type, e.getMessage());
    }

    private WebClient buildWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(READ_TIMEOUT);
        return WebClient.builder()
                .baseUrl(properties.getUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}