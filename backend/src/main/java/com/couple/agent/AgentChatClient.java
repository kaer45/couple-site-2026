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
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Agent 对话客户端：消费 Python /v1/chat 的 SSE 流
 *
 * - 超时：连接 3s / 块间隔 30s / 整体 35s
 * - 熔断：连续 5 次失败 → 30s 熔断窗口，期间直接返回 CIRCUIT_OPEN（不真正发起请求）
 * - 失败分类（决定降级文案）：DISABLED / CIRCUIT_OPEN / UNREACHABLE / TIMEOUT / FAILED
 * - 真流式：传入 onDelta 回调即可"每收到一段就立刻交出去"，调用方据此实时下发前端；
 *   内部同时累积完整文本（ChatCallResult.deltas / fullText()）供落库使用
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
    /** 摘要属于批量任务，比对话慢，给更长的整体超时 */
    private static final Duration SUMMARIZE_TIMEOUT = Duration.ofSeconds(60);

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

    /** /v1/summarize 调用结果 */
    @Data
    public static class SummarizeResult {
        private boolean ok;
        private String summary;
        private String errorType;   // DISABLED / CIRCUIT_OPEN / UNREACHABLE / TIMEOUT / FAILED
        private String errorDetail;

        static SummarizeResult ok(String summary) {
            SummarizeResult r = new SummarizeResult();
            r.ok = true;
            r.summary = summary;
            return r;
        }

        static SummarizeResult error(String type, String detail) {
            SummarizeResult r = new SummarizeResult();
            r.ok = false;
            r.errorType = type;
            r.errorDetail = detail;
            return r;
        }
    }

    /**
     * 调用 Python /v1/summarize 生成内容摘要（普通 JSON 请求，非流式）
     *
     * 契约：body { trace_id, user_id, note_id, title, content }
     * 响应：{ code:0, message:"success", data:{ summary:"..." } }
     *
     * 与对话共用熔断计数；失败**不写库**，由调用方决定如何提示。
     */
    public SummarizeResult summarize(Long userId, Long noteId, String title, String content) {
        if (!properties.isEnabled()) {
            return SummarizeResult.error("DISABLED", "agent.enabled=false");
        }
        long now = System.currentTimeMillis();
        if (circuitOpen) {
            if (now - circuitOpenedAtMs > CIRCUIT_OPEN_MS) {
                circuitOpen = false; // 半开：放一次试探
            } else {
                return SummarizeResult.error("CIRCUIT_OPEN", "熔断中");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trace_id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        body.put("user_id", userId);
        body.put("note_id", noteId);
        body.put("title", title);
        body.put("content", content);

        try {
            String resp = buildWebClient(SUMMARIZE_TIMEOUT).post()
                    .uri("/v1/summarize")
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set("X-Agent-Key", properties.getKey());
                    })
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(SUMMARIZE_TIMEOUT)
                    .block(SUMMARIZE_TIMEOUT);

            JsonNode root = objectMapper.readTree(resp == null ? "{}" : resp);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && !codeNode.isNull() && codeNode.asInt() != 0) {
                return handleSummarizeFailure(null,
                        "body.code=" + codeNode.asInt() + ", message=" + root.path("message").asText(""));
            }
            JsonNode summaryNode = root.path("data").path("summary");
            String summary = (summaryNode.isMissingNode() || summaryNode.isNull()) ? null : summaryNode.asText();
            if (summary == null || summary.isBlank()) {
                return handleSummarizeFailure(null, "data.summary 为空");
            }
            consecutiveFailures.set(0);
            return SummarizeResult.ok(summary.trim());
        } catch (Exception e) {
            return handleSummarizeFailure(e, e.getMessage());
        }
    }

    /** 摘要失败分类 + 熔断计数（与对话共用阈值） */
    private SummarizeResult handleSummarizeFailure(Exception e, String detail) {
        String type = classifyFailure(e);
        if (consecutiveFailures.incrementAndGet() >= FAIL_THRESHOLD) {
            circuitOpen = true;
            circuitOpenedAtMs = System.currentTimeMillis();
            log.warn("Agent 连续失败 {} 次，熔断 30s", consecutiveFailures.get());
        }
        log.warn("Agent /v1/summarize 调用失败 [{}]: {}", type, detail);
        return SummarizeResult.error(type, detail);
    }

    public ChatCallResult call(Long userId, Long coupleId, String sessionId, String message,
                               String scope, Long scopeId) {
        return call(userId, coupleId, sessionId, message, scope, scopeId, null);
    }

    /**
     * 调用 /v1/chat 并消费 SSE 流（流式版）
     *
     * @param onDelta 每解析出一段增量就**立即**回调（可为 null）；
     *                回调内部的异常不会中断整条流（例如前端已断开导致 SSE/WS 下发失败），
     *                调用方借此把增量实时推给前端，实现真正逐字输出
     */
    public ChatCallResult call(Long userId, Long coupleId, String sessionId, String message,
                               String scope, Long scopeId, Consumer<String> onDelta) {
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

        // 增量边收边推，同时累积一份完整文本用于落库
        List<String> deltas = new ArrayList<>();
        List<Map<String, Object>> refs = new ArrayList<>();
        List<String> errorHolder = new ArrayList<>(); // Python 主动上报的 error（最多一条）

        try {
            WebClient client = buildWebClient();
            client.post()
                    .uri("/v1/chat")
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set("X-Agent-Key", properties.getKey());
                    })
                    .bodyValue(body)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(READ_TIMEOUT)                   // 块与块之间超时：Python 卡住不吐也会失败
                    .publishOn(Schedulers.boundedElastic())  // 回调里有阻塞 IO（SSE/WS 下发），切到弹性线程池，
                                                             // 避免占住 Netty 事件循环影响其它请求
                    .doOnNext(block -> consumeData(block, deltas, refs, errorHolder, onDelta))
                    .then()
                    .block(TOTAL_TIMEOUT);

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
                             List<String> errorHolder, Consumer<String> onDelta) {
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
                    String delta = content.asText();
                    deltas.add(delta);
                    emit(onDelta, delta);
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
                emit(onDelta, data);
            }
        }
    }

    /**
     * 把一段增量交给调用方实时推送。
     * 回调失败（前端已断开、SSE emitter 抛 IOException 等）只记日志，绝不因此中断整条流。
     */
    private void emit(Consumer<String> onDelta, String delta) {
        if (onDelta == null) {
            return;
        }
        try {
            onDelta.accept(delta);
        } catch (Exception e) {
            log.debug("增量下发失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /** 失败分类：决定降级文案（对话与摘要共用） */
    private String classifyFailure(Exception e) {
        if (e instanceof TimeoutException || e instanceof ReadTimeoutException
                || e instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT";
        }
        if (e instanceof WebClientResponseException) {
            // 4xx 与 5xx 目前都归 FAILED
            return "FAILED";
        }
        return "UNREACHABLE"; // 连接失败（拒连/协议错误等）
    }

    private ChatCallResult handleFailure(Exception e) {
        String type = classifyFailure(e);
        if (consecutiveFailures.incrementAndGet() >= FAIL_THRESHOLD) {
            circuitOpen = true;
            circuitOpenedAtMs = System.currentTimeMillis();
            log.warn("Agent 连续失败 {} 次，熔断 30s", consecutiveFailures.get());
        }
        log.warn("Agent /v1/chat 调用失败 [{}]: {}", type, e.getMessage());
        return ChatCallResult.error(type, e.getMessage());
    }

    private WebClient buildWebClient() {
        return buildWebClient(READ_TIMEOUT);
    }

    /** 读超时可按调用场景调整（摘要比对话慢） */
    private WebClient buildWebClient(Duration readTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(readTimeout);
        return WebClient.builder()
                .baseUrl(properties.getUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}