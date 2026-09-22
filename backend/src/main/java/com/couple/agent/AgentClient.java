package com.couple.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 客户端：与 Python AI 服务的 ingest 接口通信
 *
 * - 幂等：同 (bizType, bizId, action) 在 5s 窗口内去重（防止同一次操作被重复触发）
 * - 重试：失败按 5s / 30s / 2min 重试 3 次，仍失败记 error 日志放弃（不阻塞主流程）
 * - 总开关：agent.enabled=false 时全部静默跳过
 *
 * 请求体契约定死（Python 侧按此实现）：
 * POST /v1/ingest { trace_id, biz_type, biz_id, content, scope, scope_id, action }
 * 鉴权：X-Agent-Key 共享密钥
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentClient {

    /** 幂等去重窗口：同一条业务数据 5s 内的重复触发直接跳过 */
    private static final long DEDUP_WINDOW_MS = 5_000L;

    /** 重试延迟序列：5s / 30s / 2min（3 次） */
    private static final long[] RETRY_DELAYS_MS = {5_000L, 30_000L, 120_000L};

    private final AgentProperties properties;
    private final RestTemplate agentRestTemplate;

    /** 去重键 → 最近调用时间戳 */
    private final Map<String, Long> recentCalls = new ConcurrentHashMap<>();

    public void ingest(String bizType, Long bizId, String content, String scope, Long scopeId) {
        send(bizType, bizId, content, scope, scopeId, "upsert");
    }

    public void delete(String bizType, Long bizId, String scope, Long scopeId) {
        send(bizType, bizId, null, scope, scopeId, "delete");
    }

    private void send(String bizType, Long bizId, String content, String scope, Long scopeId, String action) {
        if (!properties.isEnabled()) {
            return;
        }
        String dedupKey = bizType + ":" + bizId + ":" + action;
        long now = System.currentTimeMillis();
        Long last = recentCalls.get(dedupKey);
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            log.debug("ingest 幂等跳过: {}", dedupKey);
            return;
        }
        recentCalls.put(dedupKey, now);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trace_id", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        body.put("biz_type", bizType);
        body.put("biz_id", bizId);
        body.put("content", content);
        body.put("scope", scope);
        body.put("scope_id", scopeId);
        body.put("action", action);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Agent-Key", properties.getKey());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        String url = properties.getUrl() + "/v1/ingest";
        for (int attempt = 0; attempt < RETRY_DELAYS_MS.length; attempt++) {
            try {
                agentRestTemplate.postForEntity(url, entity, String.class);
                log.info("ingest 成功: bizType={}, bizId={}, action={}", bizType, bizId, action);
                return;
            } catch (Exception e) {
                log.warn("ingest 调用失败 attempt={}/3: bizType={}, bizId={}, action={}, err={}",
                        attempt + 1, bizType, bizId, action, e.getMessage());
                if (attempt < RETRY_DELAYS_MS.length - 1) {
                    sleep(RETRY_DELAYS_MS[attempt]);
                }
            }
        }
        log.error("ingest 重试耗尽仍失败: bizType={}, bizId={}, action={}（可稍后人工补同步）", bizType, bizId, action);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}